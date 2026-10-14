package com.testplatform.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 生成端到端验证（@Tag("manual")：真实调用网关 + 上游 LLM，产生真实费用）
 *
 * 不进日常回归！运行方式：mvn test -Dgroups=manual
 * 验证目标：契约 → 网关 → 解析 → 落库 pending 的全链路产出 ≥1 条用例
 */
@Tag("manual")
@Tag("integration")
class AiCaseGenerationManualTest {

    @Test
    @DisplayName("AI 生成：注册接口契约 → 至少产出 1 条 pending 用例")
    void generateCasesForRegisterContract() throws Exception {
        int count = AiCaseGenerator.generateForContract("contracts/user-register.yaml");
        System.out.println("AI 生成用例数: " + count);
        assertTrue(count >= 1, "AI 至少应产出 1 条用例");
    }
}
