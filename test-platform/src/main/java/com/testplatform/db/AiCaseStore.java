package com.testplatform.db;

import com.testplatform.config.TestConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 用例存储层：原生 JDBC（不引 ORM/连接池）
 *
 * 选型理由：平台当前只有一张表三种操作——
 * 引 MyBatis-Plus/HikariCP 是为炫技付运维成本；一张表撑不起一个框架。
 * 教学点：选型规模匹配复杂度（等演进到多表+高频再上池化）
 */
public final class AiCaseStore {

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(
                TestConfig.dbUrl(), TestConfig.dbUser(), TestConfig.dbPassword());
    }

    /** 新增 AI 生成用例（初始状态 pending——人工复核前永不进回归） */
    public static long insertPending(String caseName, String contractPath,
                                     String requestJson, String expectJson) throws SQLException {
        String sql = "INSERT INTO ai_test_case "
                + "(case_name, contract_path, request_json, expect_json, status) "
                + "VALUES (?, ?, ?, ?, 'pending')";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, caseName);
            ps.setString(2, contractPath);
            ps.setString(3, requestJson);
            ps.setString(4, expectJson);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 按状态列出（复核台看 pending；回归引擎读 approved） */
    public static List<AiCase> listByStatus(String status) throws SQLException {
        String sql = "SELECT id, case_name, contract_path, request_json, expect_json, status, review_note "
                + "FROM ai_test_case WHERE status = ? AND deleted = 0 ORDER BY id";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                List<AiCase> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new AiCase(
                            rs.getLong("id"),
                            rs.getString("case_name"),
                            rs.getString("contract_path"),
                            rs.getString("request_json"),
                            rs.getString("expect_json"),
                            rs.getString("status"),
                            rs.getString("review_note")));
                }
                return list;
            }
        }
    }

    /** 复核裁决：approve / rejected + 备注（人工改判是状态机唯一的推进方式） */
    public static int review(long id, String targetStatus, String note) throws SQLException {
        String sql = "UPDATE ai_test_case SET status = ?, review_note = ? "
                + "WHERE id = ? AND status = 'pending' AND deleted = 0";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, targetStatus);
            ps.setString(2, note);
            ps.setLong(3, id);
            return ps.executeUpdate(); // 0 行 = 用例不存在或已复核过（幂等保护）
        }
    }

    /**
     * 用例下线：approved → rejected 的治理改判（契约变更/用例过时/复核后发现缺陷）
     *
     * 与 review 的守门差异：review 只推进 pending；deprecate 只改判 approved。
     * 单向性保证：rejected 的用例永远不能直接回到 approved——有问题的用例
     * 必须修正后作为新用例重新走 pending → 人工复核（状态机不给"带病复活"开门）
     */
    public static int deprecate(long id, String note) throws SQLException {
        String sql = "UPDATE ai_test_case SET status = ?, review_note = ? "
                + "WHERE id = ? AND status = 'approved' AND deleted = 0";
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AiCase.REJECTED);
            ps.setString(2, note);
            ps.setLong(3, id);
            return ps.executeUpdate();
        }
    }

    private AiCaseStore() {
    }
}
