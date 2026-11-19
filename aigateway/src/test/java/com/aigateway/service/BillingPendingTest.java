package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.config.BillingProperties;
import com.aigateway.config.LlmRouteProperties;
import com.aigateway.entity.BillingRecord;
import com.aigateway.entity.User;
import com.aigateway.mapper.BillingRecordMapper;
import com.aigateway.mapper.UserMapper;
import com.aigateway.util.ApiKeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/** 冻结受理单元测试：只验证业务分支，不连接真实数据库或 Redis。 */
@ExtendWith(MockitoExtension.class)
class BillingPendingTest {

    @Mock private BillingRecordMapper billingRecordMapper;
    @Mock private UserMapper userMapper;
    @Mock private ApiKeyGenerator apiKeyGenerator;
    @Mock private StringRedisTemplate redisTemplate;

    private BillingService newService() {
        BillingProperties billingProperties = new BillingProperties();
        LlmRouteProperties routeProperties = new LlmRouteProperties();
        return new BillingService(billingRecordMapper, userMapper, apiKeyGenerator,
                redisTemplate, billingProperties, routeProperties);
    }

    @Test
    @DisplayName("可用余额不足：不写处理中账单")
    void insufficientBalanceDoesNotCreatePendingRecord() {
        when(apiKeyGenerator.sha256Hex(any())).thenReturn("hash-1");
        when(userMapper.freezeBalance(7L, 200L)).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> newService().createPending(3L, 7L, "r-1", "model", "[]", 200L));

        assertEquals(403, error.getCode());
        verify(billingRecordMapper, never()).insert(any(BillingRecord.class));
    }

    @Test
    @DisplayName("首次请求：冻结金额并写入处理中账单")
    void firstRequestCreatesPendingRecord() {
        when(apiKeyGenerator.sha256Hex(any())).thenReturn("hash-1");
        when(userMapper.freezeBalance(7L, 200L)).thenReturn(1);
        when(billingRecordMapper.insert(any(BillingRecord.class))).thenAnswer(invocation -> {
            BillingRecord record = invocation.getArgument(0);
            record.setId(10L);
            return 1;
        });

        BillingRecord result = newService().createPending(3L, 7L, "r-1", "model", "[]", 200L);

        assertEquals(BillingRecord.Status.PROCESSING, result.getStatus());
        assertEquals(200L, result.getFrozenAmount());
        verify(userMapper).freezeBalance(7L, 200L);
        verify(billingRecordMapper).insert(any(BillingRecord.class));
    }

    @Test
    @DisplayName("同请求编号：相同指纹返回原账单，不重复冻结")
    void sameRequestIsIdempotent() {
        when(apiKeyGenerator.sha256Hex(any())).thenReturn("hash-1");
        BillingRecord existing = new BillingRecord();
        existing.setRequestHash("hash-1");
        existing.setStatus(BillingRecord.Status.PROCESSING);
        when(billingRecordMapper.selectOne(any())).thenReturn(existing);

        BillingRecord result = newService().createPending(3L, 7L, "r-1", "model", "[]", 200L);

        assertEquals(existing, result);
        verify(userMapper, never()).freezeBalance(anyLong(), anyLong());
        verify(billingRecordMapper, never()).insert(any(BillingRecord.class));
    }

    @Test
    @DisplayName("待核对及重复标记：保留冻结与首次原因，不修改账户")
    void reconciliationPreservesMoneyAndFirstReason() {
        BillingRecord record = pendingRecord();
        when(billingRecordMapper.updateById(record)).thenReturn(1);
        BillingService service = newService();

        service.markReconciliationRequired(10L, "模型用量缺失");
        service.markReconciliationRequired(10L, "后续结算失败");

        assertEquals(BillingRecord.Status.RECONCILIATION_REQUIRED, record.getStatus());
        assertEquals("模型用量缺失", record.getFailureReason());
        assertEquals(200L, record.getFrozenAmount());
        verify(billingRecordMapper).updateById(record);
        verifyNoInteractions(userMapper);
        assertEquals(409, assertThrows(BizException.class,
                () -> service.releasePending(10L, "结果不确定")).getCode());
        assertEquals(409, assertThrows(BizException.class,
                () -> service.settlePending(10L, 1L)).getCode());
    }

    @Test
    @DisplayName("已结算或已释放账单：不能改回待核对")
    void reconciliationDoesNotDowngradeTerminalStates() {
        BillingRecord record = pendingRecord();
        BillingService service = newService();
        for (BillingRecord.Status status : new BillingRecord.Status[]{
                BillingRecord.Status.SETTLED, BillingRecord.Status.RELEASED}) {
            record.setStatus(status);
            service.markReconciliationRequired(10L, "后续查询失败");
            assertEquals(status, record.getStatus());
        }
        verify(billingRecordMapper, never()).updateById(any(BillingRecord.class));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("核对原因非法：拒绝且不查询账单")
    void reconciliationValidatesReason() {
        BillingService service = newService();
        for (String reason : new String[]{null, " ", "错".repeat(501)}) {
            assertEquals(400, assertThrows(BizException.class,
                    () -> service.markReconciliationRequired(10L, reason)).getCode());
        }
        verifyNoInteractions(userMapper, billingRecordMapper);
    }

    @Test
    @DisplayName("待核对标记保存失败：抛出异常，不修改账户")
    void reconciliationPersistenceFailureIsNotSwallowed() {
        pendingRecord();
        assertEquals(409, assertThrows(BizException.class,
                () -> newService().markReconciliationRequired(10L, "结算结果不确定")).getCode());
        verifyNoInteractions(userMapper);
    }

    private BillingRecord pendingRecord() {
        BillingRecord record = new BillingRecord();
        record.setId(10L);
        record.setUserId(7L);
        record.setFrozenAmount(200L);
        record.setStatus(BillingRecord.Status.PROCESSING);
        when(billingRecordMapper.selectByIdForUpdate(10L)).thenReturn(record);
        return record;
    }

    @Test
    @DisplayName("首次结算：扣实际费用，解除全部冻结并保存已结算状态")
    void settlementUpdatesAccountAndRecord() {
        BillingRecord record = pendingRecord();
        User user = new User();
        user.setBalance(9999L);
        when(userMapper.settleFrozenBalance(7L, 200L, 1L)).thenReturn(1);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(billingRecordMapper.updateById(record)).thenReturn(1);

        newService().settlePending(10L, 1L);

        assertEquals(BillingRecord.Status.SETTLED, record.getStatus());
        assertEquals(1L, record.getActualCost());
        assertEquals(9999L, record.getBalanceAfter());
        verify(userMapper).settleFrozenBalance(7L, 200L, 1L);
        verify(billingRecordMapper).updateById(record);
    }

    @Test
    @DisplayName("重复结算：相同费用返回原账单，不再次修改账户")
    void repeatedSettlementDoesNotChangeBalance() {
        BillingRecord record = pendingRecord();
        record.setStatus(BillingRecord.Status.SETTLED);
        record.setActualCost(1L);
        newService().settlePending(10L, 1L);
        verify(userMapper, never()).settleFrozenBalance(anyLong(), anyLong(), anyLong());
        verify(billingRecordMapper, never()).updateById(any(BillingRecord.class));
        assertEquals(409, assertThrows(BizException.class,
                () -> newService().settlePending(10L, 2L)).getCode());
    }

    @Test
    @DisplayName("释放冻结及重复释放：只修改一次冻结金额，不扣总余额")
    void releaseIsIdempotent() {
        BillingRecord record = pendingRecord();
        when(userMapper.releaseFrozenBalance(7L, 200L)).thenReturn(1);
        when(billingRecordMapper.updateById(record)).thenReturn(1);
        BillingService service = newService();
        service.releasePending(10L, "确认未发生消费");
        service.releasePending(10L, "确认未发生消费");
        assertEquals(BillingRecord.Status.RELEASED, record.getStatus());
        verify(userMapper).releaseFrozenBalance(7L, 200L);
        verify(userMapper, never()).settleFrozenBalance(anyLong(), anyLong(), anyLong());
        verify(billingRecordMapper).updateById(record);
        assertEquals(409, assertThrows(BizException.class,
                () -> service.settlePending(10L, 1L)).getCode());
    }

    @Test
    @DisplayName("费用超额或账户异常：不保存结算状态")
    void invalidSettlementIsRejected() {
        BillingRecord record = pendingRecord();
        BillingService service = newService();
        assertEquals(409, assertThrows(BizException.class,
                () -> service.settlePending(10L, 201L)).getCode());
        verify(userMapper, never()).settleFrozenBalance(anyLong(), anyLong(), anyLong());
        assertEquals(409, assertThrows(BizException.class,
                () -> service.settlePending(10L, 1L)).getCode());
        assertEquals(BillingRecord.Status.PROCESSING, record.getStatus());
        verify(billingRecordMapper, never()).updateById(any(BillingRecord.class));
    }

    @Test
    @DisplayName("已结算账单不能释放；状态保存失败必须抛出异常")
    void settlementCannotBeReleasedAndPersistenceFailurePropagates() {
        BillingRecord record = pendingRecord();
        record.setStatus(BillingRecord.Status.SETTLED);
        BillingService service = newService();
        assertEquals(409, assertThrows(BizException.class,
                () -> service.releasePending(10L, "确认未消费")).getCode());
        verify(userMapper, never()).releaseFrozenBalance(anyLong(), anyLong());

        record.setStatus(BillingRecord.Status.PROCESSING);
        when(userMapper.releaseFrozenBalance(7L, 200L)).thenReturn(1);
        assertEquals(409, assertThrows(BizException.class,
                () -> service.releasePending(10L, "确认未消费")).getCode());
    }
}
