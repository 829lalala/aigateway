package com.testplatform.db;

/**
 * AI 生成测试用例（ai_test_case 表的投影）
 *
 * status 状态机：pending（待复核）→ approved（进回归）/ rejected（否决）
 * request/expect 用 JSON 字符串承载（结构随接口差异而变，宽表 + JSON 是用例库的常规取型）
 */
public record AiCase(
        Long id,
        String caseName,
        String contractPath,
        String requestJson,
        String expectJson,
        String status,
        String reviewNote) {

    /** 复核通过状态常量（回归引擎只加载 approved） */
    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
}
