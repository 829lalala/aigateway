package com.testplatform.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 用例库存储层测试：状态机语义 + 幂等保护（纯 DB 操作，无 LLM 依赖，进日常回归）
 */
@Tag("integration")
class AiCaseStoreTest {

    @Test
    @DisplayName("状态机：insert 默认 pending → approve 进回归集 → 重复复核被幂等拦截")
    void reviewStateMachineAndIdempotency() throws Exception {
        long id = AiCaseStore.insertPending(
                "存储层测试-临时用例",
                "TEST /internal",
                "{\"method\":\"GET\",\"path\":\"/ping\"}",
                "{\"httpStatus\":200}");

        // 初始状态：pending，且能被复核台看到
        List<AiCase> pending = AiCaseStore.listByStatus(AiCase.PENDING);
        assertTrue(pending.stream().anyMatch(c -> c.id() == id), "新增用例应出现在 pending 列表");

        // approve：0→1 行更新，状态迁移
        assertEquals(1, AiCaseStore.review(id, AiCase.APPROVED, null));
        assertTrue(AiCaseStore.listByStatus(AiCase.PENDING).stream().noneMatch(c -> c.id() == id),
                "approve 后应离开 pending 列表");
        assertTrue(AiCaseStore.listByStatus(AiCase.APPROVED).stream().anyMatch(c -> c.id() == id),
                "approve 后应进入回归集");

        // 幂等保护：已 approved 的用例再被 reject → 0 行（状态机只允许 pending 推进）
        assertEquals(0, AiCaseStore.review(id, AiCase.REJECTED, "试图篡改已通过用例"));

        // 用例下线：approved → rejected 的治理改判（复核后发现缺陷的唯一补救路径）
        assertEquals(1, AiCaseStore.deprecate(id, "复核后发现用例缺陷，下线"));
        assertTrue(AiCaseStore.listByStatus(AiCase.APPROVED).stream().noneMatch(c -> c.id() == id),
                "下线后应离开回归集");
        // 下线后不可"带病复活"：再 deprecate 也 0 行（已不在 approved）
        assertEquals(0, AiCaseStore.deprecate(id, "再次下线"));

        // 清理：测试数据逻辑删除，不污染真实用例库
        cleanup(id);
        List<AiCase> approved = AiCaseStore.listByStatus(AiCase.APPROVED);
        assertFalse(approved.stream().anyMatch(c -> c.id() == id), "清理后不应再出现在回归集");
    }

    private void cleanup(long id) throws Exception {
        try (var c = java.sql.DriverManager.getConnection(
                com.testplatform.config.TestConfig.dbUrl(),
                com.testplatform.config.TestConfig.dbUser(),
                com.testplatform.config.TestConfig.dbPassword());
             var ps = c.prepareStatement("UPDATE ai_test_case SET deleted = 1 WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
