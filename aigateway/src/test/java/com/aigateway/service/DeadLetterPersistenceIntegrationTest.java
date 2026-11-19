package com.aigateway.service;

import com.aigateway.mapper.DeadLetterRecordMapper;
import com.aigateway.mapper.UserMapper;
import com.aigateway.entity.User;
import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.util.JwtUtil;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

/** 在真实 MySQL 中验证去重不会覆盖已有处理状态；测试数据由事务回滚。 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "logging.level.org.springframework=INFO",
        "logging.level.org.springframework.web=INFO",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
@Transactional
class DeadLetterPersistenceIntegrationTest {
    @Autowired DeadLetterRecordMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext webContext;
    @Autowired JwtUtil jwt;
    @Autowired UserMapper users;
    // 集成测试使用真实网页路由和数据库，仅替换发送端，避免触发现有库存发码。
    @MockitoBean KafkaTemplate<String, String> kafka;

    @Test
    void stalledRetryAlertChecksAgeStatusAndPermissionsWithoutChangingRecords() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String username = "dlt-alert-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO `user` (username,password,email,role) VALUES (?,?,?,0)",
                username, "unused-test-password", username + "@example.test");
        Long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        String token = "Bearer " + jwt.createToken(userId, username);
        String path = "/admin/dead-letters/alerts";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
        User admin = new User(); admin.setId(userId); admin.setRole(1);
        users.updateById(admin);
        long baseline = mapper.countStalledRetries();
        mvc.perform(get(path).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggered").value(baseline > 0))
                .andExpect(jsonPath("$.data.affectedCount").value(baseline));

        var databaseNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.time.LocalDateTime.class);
        java.util.List<Long> fixtureIds = new java.util.ArrayList<>();
        // 旧重试、时间缺失、新重试、未来时间、旧但已重投，只有前两项应触发。
        for (int i = 0; i < 5; i++) {
            var record = retryFixture();
            record.setStatus(i == 4 ? "REPUBLISHED" : "RETRYING");
            record.setRetryCount(1);
            record.setLastRetryTime(switch (i) {
                case 0, 4 -> databaseNow.minusMinutes(10);
                case 1 -> null;
                case 2 -> databaseNow;
                default -> databaseNow.plusMinutes(10);
            });
            mapper.insert(record);
            fixtureIds.add(record.getId());
        }
        mvc.perform(get(path).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rule").value("DEAD_LETTER_RETRY_STALLED"))
                .andExpect(jsonPath("$.data.triggered").value(true))
                .andExpect(jsonPath("$.data.affectedCount").value(baseline + 2))
                .andExpect(jsonPath("$.data.thresholdMinutes").value(5))
                .andExpect(jsonPath("$.data.payload").doesNotExist());
        for (int i = 0; i < fixtureIds.size(); i++) {
            var stored = mapper.selectById(fixtureIds.get(i));
            assertEquals(i == 4 ? "REPUBLISHED" : "RETRYING", stored.getStatus());
            assertEquals(1, stored.getRetryCount());
            mapper.deleteById(stored.getId());
        }
        assertEquals(baseline, mapper.countStalledRetries());
        verifyNoInteractions(kafka);
    }

    @Test
    void metricsRequireAdminAndReflectAllStatusesWithoutExposingPayload() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String username = "dlt-metric-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO `user` (username,password,email,role) VALUES (?,?,?,0)",
                username, "unused-test-password", username + "@example.test");
        Long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        String token = "Bearer " + jwt.createToken(userId, username);
        String path = "/admin/dead-letters/metrics";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
        User admin = new User(); admin.setId(userId); admin.setRole(1);
        users.updateById(admin);
        var before = mapper.metrics();
        for (String state : new String[]{"PENDING", "RETRYING", "REPUBLISHED", "FAILED"}) {
            DeadLetterRecord record = retryFixture();
            record.setStatus(state);
            record.setRetryCount(2);
            record.setCreateTime(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
            mapper.insert(record);
        }
        var after = mapper.metrics();
        assertEquals(before.total() + 4, after.total());
        assertEquals(before.pending() + 1, after.pending());
        assertEquals(before.retrying() + 1, after.retrying());
        assertEquals(before.republished() + 1, after.republished());
        assertEquals(before.failed() + 1, after.failed());
        assertEquals(before.retryAttempts() + 8, after.retryAttempts());
        var fixtureTime = java.time.LocalDateTime.of(2000, 1, 1, 0, 0);
        assertEquals(before.oldestPendingTime() == null || before.oldestPendingTime().isAfter(fixtureTime)
                ? fixtureTime : before.oldestPendingTime(), after.oldestPendingTime());
        mvc.perform(get(path).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(after.total()))
                .andExpect(jsonPath("$.data.pending").value(after.pending()))
                .andExpect(jsonPath("$.data.retryAttempts").value(after.retryAttempts()))
                .andExpect(jsonPath("$.data.payload").doesNotExist());
    }

    @Test
    void adminQueryEnforcesPermissionsFiltersAndStablePagination() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String username = "dlt-query-" + suffix;
        jdbc.update("INSERT INTO `user` (username,password,email,role) VALUES (?,?,?,0)",
                username, "unused-test-password", username + "@example.test");
        Long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        String token = "Bearer " + jwt.createToken(userId, username);
        String topic = "query-check-" + suffix + ".DLT";
        // 使用相同创建时间，验证第二排序字段能保证跨页顺序稳定。
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO dead_letter_record
                    (dlt_topic,dlt_partition,dlt_offset,payload,status,create_time)
                    VALUES (?,0,?,?,?,'2026-01-01 00:00:00')
                    """, topic, i, "query-fixture-" + i, i == 2 ? "FAILED" : "PENDING");
        }
        String path = "/admin/dead-letters";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
        User admin = new User();
        admin.setId(userId);
        admin.setRole(1);
        users.updateById(admin);
        mvc.perform(get(path).header("Authorization", token)
                        .param("topic", topic).param("status", "PENDING").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].dltOffset").value(1));
        mvc.perform(get(path).header("Authorization", token)
                        .param("topic", topic).param("status", "PENDING").param("size", "1").param("page", "2"))
                .andExpect(jsonPath("$.data.records[0].dltOffset").value(0));
        mvc.perform(get(path).header("Authorization", token)
                        .param("topic", topic).param("page", "0").param("size", "999"))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get(path).header("Authorization", token).param("topic", topic + "-missing"))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get(path).header("Authorization", token).param("topic", topic).param("size", "0"))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1));
        mvc.perform(get(path).header("Authorization", token).param("page", Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void retryEndpointChecksRoleAndSendsOnlyOnce() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String username = "dlt-retry-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO `user` (username,password,email,role) VALUES (?,?,?,0)",
                username, "unused-test-password", username + "@example.test");
        Long userId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?", Long.class, username);
        String token = "Bearer " + jwt.createToken(userId, username);
        DeadLetterRecord record = retryFixture();
        mapper.insert(record);
        String path = "/admin/dead-letters/" + record.getId() + "/retry";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", token)).andExpect(status().isForbidden());
        verifyNoInteractions(kafka);
        User admin = new User(); admin.setId(userId); admin.setRole(1);
        users.updateById(admin);
        when(kafka.send(GiftCodeService.SEIZE_TOPIC, "retry-test-key", "{\"userId\":42}"))
                .thenReturn(CompletableFuture.completedFuture(null));
        mvc.perform(post(path).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REPUBLISHED"));
        mvc.perform(post(path).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(409));
        verify(kafka, times(1)).send(GiftCodeService.SEIZE_TOPIC, "retry-test-key", "{\"userId\":42}");
        DeadLetterRecord stored = mapper.selectById(record.getId());
        assertEquals("REPUBLISHED", stored.getStatus());
        assertEquals(1, stored.getRetryCount());
        assertNotNull(stored.getLastRetryTime());
        mvc.perform(post("/admin/dead-letters/0/retry").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(400));
        // 查询不存在的正整数编号，不依赖数据库自增序列的当前位置。
        mvc.perform(post("/admin/dead-letters/" + Long.MAX_VALUE + "/retry").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(404));
    }

    private DeadLetterRecord retryFixture() {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setDltTopic(GiftCodeService.SEIZE_TOPIC + ".DLT");
        record.setOriginalTopic(GiftCodeService.SEIZE_TOPIC);
        record.setDltPartition(0);
        // 测试消息位置使用负值，避免与真实 Kafka 的非负消息位置冲突。
        record.setDltOffset(-Math.max(1, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE));
        record.setMessageKey("retry-test-key");
        record.setPayload("{\"userId\":42}");
        return record;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRetryClaimsHaveOnlyOneWinner() throws Exception {
        DeadLetterRecord record = retryFixture();
        mapper.insert(record);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return mapper.claimRetry(record.getId()); });
            var second = executor.submit(() -> { start.await(); return mapper.claimRetry(record.getId()); });
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            var stored = mapper.selectById(record.getId());
            assertEquals("RETRYING", stored.getStatus());
            assertEquals(1, stored.getRetryCount());
            assertNotNull(stored.getLastRetryTime());
            assertEquals(1, mapper.recordRetryError(record.getId(), "RETRYING", "结果不确定"));
            assertEquals(0, mapper.claimRetry(record.getId()));
            // 明确未发送后允许再次领取，次数只在成功领取资格时递增。
            assertEquals(1, mapper.recordRetryError(record.getId(), "FAILED", "明确未发送"));
            assertEquals(1, mapper.claimRetry(record.getId()));
            assertNull(mapper.selectById(record.getId()).getLastError());
            assertEquals(2, mapper.selectById(record.getId()).getRetryCount());
            assertEquals(1, mapper.markRepublished(record.getId()));
            assertEquals(0, mapper.claimRetry(record.getId()));
            assertEquals(0, mapper.recordRetryError(record.getId(), "FAILED", "不可覆盖已确认状态"));
        } finally {
            // 此方法刻意不使用事务以验证并发竞争，因此只删除自己创建的记录。
            mapper.deleteById(record.getId());
        }
    }

    @Test
    void duplicateDeliveryPreservesOriginalPayloadAndRetryState() {
        String topic = "persistence-check-" + UUID.randomUUID() + ".DLT";
        var consumer = new DeadLetterConsumer(mapper);
        consumer.onDeadLetter(new ConsumerRecord<>(topic, 0, 1L, null, "original"));
        jdbc.update("UPDATE dead_letter_record SET status='REPUBLISHED',retry_count=2 WHERE dlt_topic=?", topic);
        consumer.onDeadLetter(new ConsumerRecord<>(topic, 0, 1L, null, "replacement"));
        var rows = jdbc.queryForList("SELECT payload,status,retry_count FROM dead_letter_record WHERE dlt_topic=?", topic);
        assertEquals(1, rows.size());
        assertEquals("original", rows.get(0).get("payload"));
        assertEquals("REPUBLISHED", rows.get(0).get("status"));
        assertEquals(2, ((Number) rows.get(0).get("retry_count")).intValue());
    }
}
