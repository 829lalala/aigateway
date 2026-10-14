package com.aigateway.service;

import com.aigateway.common.AppContext;
import com.aigateway.common.BizException;
import com.aigateway.dto.ChatRequest;
import com.aigateway.entity.Post;
import com.aigateway.entity.SummaryReceipt;
import com.aigateway.mapper.PostMapper;
import com.aigateway.mapper.SummaryReceiptMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 摘要服务（阶段 17 可靠消费版）：受理单状态机 + 事件触发 + 重试上抛
 *
 * 流程（与阶段 15 at-most-once 版的本质差异）：
 *   dispatch：校验 → 【落受理单 PROCESSING】→ 发事件（payload 带 receiptId）→ 202
 *   消费（17.3）：generateAndFill(receiptId) → 成功【置 READY+summary】
 *                失败【上抛异常】→ @RetryableTopic 非阻塞重试 ×3 → 耗尽进 DLT →【置 FAILED+reason】
 * 查询：status(postId) 三态（processing/ready/failed）——失败从"永远 pending"变成"可见可追溯"
 *
 * 失败上抛的语义变化（对比 15 版 catch-all 丢弃）：
 *   15 版：消费失败 → 丢弃（用户永远 pending）
 *   17 版：消费失败 → 异常传播触发重试 → 耗尽由 DLT 处理器置 FAILED——
 *          "无限重试堵死分区"的担忧由 @RetryableTopic 的非阻塞重试解决（消息发到 retry topic）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final PostMapper postMapper;

    private final SummaryReceiptMapper receiptMapper;

    private final ChatService chatService;

    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 摘要事件 topic（17.3 配置 @RetryableTopic 重试/死信拓扑） */
    public static final String SUMMARY_TOPIC = "post-summary-events";

    private static final JsonMapper EVENT_JSON = JsonMapper.builder().build();

    /** 内部调用标识：app_id=0 即"非 API Key 通道"（账单可观测） */
    private static final long INTERNAL_APP_ID = 0L;
    private static final String INTERNAL_APP_NAME = "community";

    /** 摘要模型：轻任务固定最便宜模型；路由链首，失败自动沿链降级 */
    private static final String SUMMARY_MODEL = "glm-4-flash";

    /** 输入限幅：正文前 N 字 */
    private static final int CONTENT_SNIPPET_LEN = 500;

    /** 输出上限（预扣估算基准） */
    private static final int SUMMARY_MAX_TOKENS = 200;

    /**
     * 受理：校验 → 【落受理单 PROCESSING】→ 发事件（payload 带 receiptId）
     *
     * 顺序保证：先落单再发事件——受理单是"用户已请求"的事实，事件只是触发器；
     * 事件发送失败则回写 FAILED（孤儿单处置：不留无声的 PROCESSING）
     */
    public Map<String, Object> dispatch(Long postId, Long userId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(404, "帖子不存在");
        }
        if (!post.getUserId().equals(userId)) {
            throw new BizException(403, "只有作者本人才能生成摘要");
        }

        // 受理单先于事件落库（receiptId 是事件与状态回写的关联键）
        SummaryReceipt receipt = new SummaryReceipt();
        receipt.setReceiptId(UUID.randomUUID().toString());
        receipt.setPostId(postId);
        receipt.setStatus(SummaryReceipt.PROCESSING);
        receiptMapper.insert(receipt);

        try {
            String message = EVENT_JSON.writeValueAsString(
                    Map.of("receiptId", receipt.getReceiptId(), "postId", postId));
            kafkaTemplate.send(SUMMARY_TOPIC, message);
            log.info("摘要事件已入队: postId={}, receiptId={}", postId, receipt.getReceiptId());
        } catch (Exception e) {
            // 事件发送失败：受理单置 FAILED（诚实告知用户失败，而非无声 pending）
            receiptMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SummaryReceipt>()
                    .eq(SummaryReceipt::getId, receipt.getId())
                    .set(SummaryReceipt::getStatus, SummaryReceipt.FAILED)
                    .set(SummaryReceipt::getFailReason, "事件发送失败: " + e.getMessage()));
            throw new BizException(502, "摘要受理失败，请稍后重试");
        }

        return Map.of(
                "receiptId", receipt.getReceiptId(),
                "postId", postId,
                "status", "processing");
    }

    /** 摘要状态查询（前端轮询端点）：最新一笔受理单的三态 */
    public Map<String, Object> status(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(404, "帖子不存在");
        }
        SummaryReceipt latest = receiptMapper.selectOne(new LambdaQueryWrapper<SummaryReceipt>()
                .eq(SummaryReceipt::getPostId, postId)
                .orderByDesc(SummaryReceipt::getId)
                .last("LIMIT 1"));

        // 无受理单：旧数据兼容（帖子早于 17 上线）——直接看 post.summary
        if (latest == null) {
            boolean legacyReady = post.getSummary() != null;
            return Map.of(
                    "postId", postId,
                    "status", legacyReady ? "ready" : "pending",
                    "summary", legacyReady ? post.getSummary() : "");
        }

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("receiptId", latest.getReceiptId());
        result.put("postId", postId);
        result.put("status", latest.getStatus().toLowerCase());
        result.put("summary", SummaryReceipt.READY.equals(latest.getStatus())
                ? latest.getSummary() : "");
        if (SummaryReceipt.FAILED.equals(latest.getStatus())) {
            result.put("failReason", latest.getFailReason());
        }
        return result;
    }

    /**
     * 消费者调用：按受理单生成并回填（17.3 @RetryableTopic 包装）
     *
     * 失败语义：异常直接上抛（触发非阻塞重试）——本方法内不吞异常；
     * 防重复：受理单已 READY（at-least-once 重投）直接跳过；
     * 成功：回填 post.summary + 受理单置 READY
     */
    public void generateAndFill(String receiptId) {
        SummaryReceipt receipt = receiptMapper.selectOne(new LambdaQueryWrapper<SummaryReceipt>()
                .eq(SummaryReceipt::getReceiptId, receiptId)
                .last("LIMIT 1"));
        if (receipt == null) {
            log.warn("受理单不存在（事件早于单落库？）: receiptId={}", receiptId);
            return;
        }
        if (SummaryReceipt.READY.equals(receipt.getStatus())) {
            log.info("受理单已完成（重复消费跳过）: receiptId={}", receiptId);
            return;
        }

        Post post = postMapper.selectById(receipt.getPostId());
        if (post == null) {
            // 帖子排队期间被删：无内容可摘要——视为业务终态（置 FAILED 而非静默）
            markFailed(receiptId, "帖子不存在（可能已被删除）");
            return;
        }

        // 组装摘要请求：system 定格式 + user 塞"标题 + 正文截断"
        ChatRequest request = buildSummaryRequest(post);

        // 模拟程序身份后直调（铁律：finally 必清）——计费记作者账，appId=0 标记内部调用
        String requestId = "summary-" + receiptId;
        Map<String, Object> response;
        try {
            AppContext.set(INTERNAL_APP_ID, INTERNAL_APP_NAME, post.getUserId());
            response = chatService.chat(request, requestId);
        } finally {
            AppContext.clear();
        }

        String summary = extractContent(response);

        Post update = new Post();
        update.setId(post.getId());
        update.setSummary(summary);
        postMapper.updateById(update);
        markReady(receiptId, summary);
        log.info("AI 摘要生成成功: postId={}, receiptId={}, requestId={}", post.getId(), receiptId, requestId);
    }

    /** 消费失败终态：SummaryConsumer 重试耗尽后调用——FAILED + 原因 */
    public void markFailed(String receiptId, String reason) {
        SummaryReceipt update = new SummaryReceipt();
        update.setStatus(SummaryReceipt.FAILED);
        update.setFailReason(reason);
        receiptMapper.update(update, new LambdaQueryWrapper<SummaryReceipt>()
                .eq(SummaryReceipt::getReceiptId, receiptId)
                .eq(SummaryReceipt::getStatus, SummaryReceipt.PROCESSING));
    }

    /** 消费成功终态：READY + 摘要 */
    private void markReady(String receiptId, String summary) {
        SummaryReceipt update = new SummaryReceipt();
        update.setStatus(SummaryReceipt.READY);
        update.setSummary(summary);
        receiptMapper.update(update, new LambdaQueryWrapper<SummaryReceipt>()
                .eq(SummaryReceipt::getReceiptId, receiptId)
                .eq(SummaryReceipt::getStatus, SummaryReceipt.PROCESSING));
    }

    /** 组装摘要请求（system 定格式 + 限幅） */
    private ChatRequest buildSummaryRequest(Post post) {
        ChatRequest request = new ChatRequest();
        request.setModel(SUMMARY_MODEL);
        request.setMaxTokens(SUMMARY_MAX_TOKENS);
        String snippet = post.getContent().length() > CONTENT_SNIPPET_LEN
                ? post.getContent().substring(0, CONTENT_SNIPPET_LEN) + "…"
                : post.getContent();

        ChatRequest.Message system = new ChatRequest.Message();
        system.setRole("system");
        system.setContent("你是社区帖子摘要助手。根据帖子标题和正文生成一段不超过 100 字的中文摘要，直接输出摘要内容，不要任何前缀或解释。");

        ChatRequest.Message user = new ChatRequest.Message();
        user.setRole("user");
        user.setContent("标题：" + post.getTitle() + "\n正文：" + snippet);
        request.setMessages(List.of(system, user));
        return request;
    }

    /** 从网关响应提取摘要文本（choices[0].message.content） */
    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new BizException(502, "上游未返回摘要内容");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = String.valueOf(message.get("content")).trim();
        if (content.isBlank()) {
            throw new BizException(502, "上游返回空摘要");
        }
        return content;
    }
}
