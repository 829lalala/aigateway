package com.aigateway.service;

import com.aigateway.common.AppContext;
import com.aigateway.common.BizException;
import com.aigateway.config.LlmRouteProperties;
import com.aigateway.dto.ChatRequest;
import com.aigateway.entity.BillingRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

// 同包嵌套类仍需显式 import（短名 StreamChunk 会被 javac 误解析为独立顶层类——13.3 踩坑）
import com.aigateway.service.StreamUsageParser.StreamChunk;

/**
 * 对话服务：代理转发到智谱大模型 API
 *
 * "代理"的语义：把用户请求原样转发给上游大模型，把响应原样返回给用户，
 * 平台本身不产生内容——这是网关的第一性职责。
 * 阶段 5 起承担计费编排：预扣 → 转发 → 实扣 → 结算（多退少补）
 * 阶段 7 起承担路由容错：沿主备模型链降级尝试，计费按实际执行模型
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RestClient zhipuRestClient;

    private final WebClient zhipuWebClient;

    private final BillingService billingService;

    private final LlmRouteProperties routeProperties;

    private final CircuitBreaker circuitBreaker;

    /** messages 序列化器（请求参数指纹的原料），服务内部自建与 Web 层解耦 */
    private static final JsonMapper CHAT_JSON = JsonMapper.builder().build();

    /**
     * 非流式对话：预扣 → 沿路由链尝试 → 实扣 → 结算
     *
     * 计费流程（双账本）：
     *   ① Redis Lua 预扣（原子，按链上最贵单价保守估算）：不足 403 拒绝
     *   ② 沿主备路由链依次尝试：上游任何错误（429/5xx/超时/400）都切换下一个
     *      ——客户端参数错误已被 Bean Validation 前置拦截，链上重试的浪费由熔断器兜底（7.3）
     *   ③ MySQL 权威扣费：计费对象是"实际执行模型"；指纹绑定"请求模型"（路由波动不影响幂等）
     *   ④ 结算：预扣按链上最贵保守估算 → 实扣必然 ≤ 预扣 → 只退不补
     *
     * @param requestId 幂等键（Controller 校验非空后传入，重试必须复用）
     * @return 智谱原始响应 + 网关附加的 billing 块（含实际执行模型）
     */
    public Map<String, Object> chat(ChatRequest request, String requestId) {
        Long appId = AppContext.getAppId();
        Long userId = AppContext.getUserId();
        String requestedModel = request.getModel(); // 用户意图（指纹绑定它，跨重试稳定）

        // 16.1 实证日志：确认请求执行线程已虚拟化（阻塞等 LLM 不再占平台线程）
        log.info("对话开始: requestId={}, thread={}, virtual={}",
                requestId, Thread.currentThread().getName(), Thread.currentThread().isVirtual());

        // ① 预扣（Redis 原子，链上最贵口径）：余额不足在此被拒，绝不调上游
        long preCost = billingService.estimatePreCost(request.getMaxTokens());
        billingService.preDeduct(userId, preCost);

        // 跨服务事务调用正常返回后，数据库扣费已经提交；后续异常不能当作扣费失败退款。
        boolean deductionCompleted = false;
        try {
            long start = System.currentTimeMillis();

            // ② 沿路由链降级尝试：任一模型成功即用，全部失败抛 502
            //    每次尝试前先过熔断器：处于打开冷却期的模型直接跳过（不再白等它的失败）
            List<String> attemptChain = buildAttemptChain(requestedModel);
            Map<String, Object> response = null;
            String servedModel = null;
            Exception lastError = null;
            int skippedByBreaker = 0; // 被熔断跳过的模型数（可观测：调用方/运维想知道降级原因）
            for (String model : attemptChain) {
                if (!circuitBreaker.tryAcquire(model)) {
                    skippedByBreaker++;
                    log.info("熔断跳过: model={} 处于打开冷却期, requestId={}", model, requestId);
                    continue;
                }
                request.setModel(model); // 复用同一 DTO，仅替换本轮尝试的模型（messages 不变）
                try {
                    response = zhipuRestClient.post()
                            .uri("/chat/completions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()      // 上游 4xx/5xx 抛 RestClientResponseException
                            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
                    servedModel = model;
                    circuitBreaker.recordSuccess(model); // 成功：计数归零、复位闭合
                    break; // 成功：后面的备选不再尝试
                } catch (RestClientException e) { // 4xx/5xx/连接失败/超时同族
                    circuitBreaker.recordFailure(model); // 连续失败达阈值 → 打开进入冷却
                    lastError = e;
                    log.warn("模型不可用，切换下一个: model={}, requestId={}, error={}",
                            model, requestId, e.getMessage());
                }
            }
            if (response == null) {
                // 链末仍失败：502 Bad Gateway（上游全挂，不是调用方的错）
                log.error("路由链全部失败: requestId={}, chain={}, lastError={}",
                        requestId, attemptChain, lastError == null ? "无" : lastError.getMessage());
                throw new BizException(502, "上游模型全部不可用，请稍后重试");
            }

            // 提取真实用量（非流式 usage 在响应顶层——阶段 3 的知识点）
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            int promptTokens = ((Number) usage.get("prompt_tokens")).intValue();
            int completionTokens = ((Number) usage.get("completion_tokens")).intValue();

            // ③ 权威扣费：指纹绑定请求模型（跨重试稳定），计价与流水绑定实际执行模型
            String messagesJson = CHAT_JSON.writeValueAsString(request.getMessages());
            BillingRecord record = billingService.deduct(appId, userId, requestId,
                    requestedModel, servedModel, messagesJson, promptTokens, completionTokens);
            deductionCompleted = true;

            // ④ 结算：预扣按链上最贵 → 实扣必然 ≤ 预扣 → 只退不补
            boolean idempotentHit = Boolean.TRUE.equals(record.getIdempotentHit());
            long chargedCost = idempotentHit ? 0L : record.getCost();
            billingService.settle(userId, preCost, chargedCost);

            // 网关增强响应：附加计费块（含实际执行模型与熔断跳过数——调用方有权知道降级细节）
            response.put("billing", Map.of(
                    "requestId", requestId,
                    "requestedModel", requestedModel,
                    "servedModel", servedModel,
                    "chargedCost", chargedCost,
                    "idempotentHit", idempotentHit,
                    "skippedByBreaker", skippedByBreaker,
                    "balance", billingService.getBalance(userId)
            ));

            log.info("对话完成: 耗时={}ms, requestId={}, servedModel={}, chargedCost={}, idempotentHit={}",
                    System.currentTimeMillis() - start, requestId, servedModel, chargedCost, idempotentHit);
            return response;
        } catch (RuntimeException e) {
            if (!deductionCompleted) {
                // 扣费调用尚未成功返回，沿用现有失败退款逻辑。
                log.warn("扣费完成前发生异常，退还预扣: requestId={}, preCost={}, 原因={}",
                        requestId, preCost, e.getMessage());
                billingService.settle(userId, preCost, 0L);
            } else {
                // 结算失败需要核对账务；查询或组装响应失败也不能再次全额退款。
                log.error("扣费已完成，后续异常不再全额退款，请核对结算状态: requestId={}, preCost={}",
                        requestId, preCost, e);
            }
            throw e;
        }
    }

    /**
     * 构造尝试链（7.0 决策：降级只在链内向右，绝不向左、绝不向链外）：
     *   请求模型在链中 → 从它的位置开始沿链向后（同链降级，尊重用户意图）
     *   请求模型不在链中 → 只尝试请求模型本身（明确指定 = 明确意图，失败不隐式替换）
     */
    private List<String> buildAttemptChain(String requestedModel) {
        List<String> chain = routeProperties.getRouteChain();
        int index = chain.indexOf(requestedModel);
        if (index >= 0) {
            return chain.subList(index, chain.size());
        }
        return List.of(requestedModel);
    }

    /**
     * 流式对话（阶段 13 计费闭环）：上游 WebClient 订阅智谱 SSE 流，下游 SseEmitter 推送
     *
     * 计费四步与非流式对齐，难点在"实扣时机"：
     *   ① 预扣（订阅前）       estimatePreCost(maxTokens) → preDeduct——与非流式同口径
     *   ② 旁路捕获（每个chunk） StreamUsageParser 双职责：透传下游 + 解析
     *                          delta.content 累计（断连估算原料）+ usage 捕获（最后块才有）
     *   ③ 实扣（流结束）       捕获到 usage → 精确实扣；断连未捕获 → 按已收文本估算扣费
     *                          （估算语义诚实写日志——有账比没账重要，断连=白嫖通道是商业漏洞）
     *   ④ 结算                 settle(preCost, actualCost) 多退少补；三路回调竞态用原子守门只结算一次
     *
     * 资源治理（阶段 3 已有）：用户中途断开/超时 => dispose 上游订阅 => 停止烧钱 + 不泄漏连接
     *
     * @param requestId 幂等键（Controller 校验非空；流式重试=新消费，键用于审计与防重放）
     */
    public SseEmitter streamChat(ChatRequest request, String requestId) {
        // 1. 强制流式（本接口语义）
        request.setStream(true);

        SseEmitter emitter = new SseEmitter(120_000L);

        Long appId = AppContext.getAppId();
        Long userId = AppContext.getUserId();
        String requestedModel = request.getModel();

        // ① 预扣（链上最贵口径，与非流式一致）：余额不足在此被拒
        long preCost = billingService.estimatePreCost(request.getMaxTokens());
        billingService.preDeduct(userId, preCost);

        long start = System.currentTimeMillis();
        log.info("转发流式对话请求: model={}, messages={}, requestId={}",
                requestedModel, request.getMessages().size(), requestId);

        // ② 旁路捕获容器（跨 reactor 线程：不可变/原子）
        StringBuilder received = new StringBuilder();
        AtomicReference<StreamUsageParser.StreamChunk.Usage> captured = new AtomicReference<>();
        AtomicBoolean settled = new AtomicBoolean(false);

        // 16.2 推送写解耦：每连接专属"单虚拟线程"串行执行器
        // emitter.send 是潜在阻塞写（Servlet flush 到慢客户端 socket）——原样跑在
        // reactor event-loop 上，一个弱网客户端就能拖住全部流式连接；
        // 单虚拟线程 = 保序（SSE 增量必须 FIFO）+ 阻塞写只挂起该 VT，event-loop 立即空闲
        ExecutorService pusher = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("sse-push-" + requestId + "-", 0).factory());
        AtomicReference<Disposable> upstreamRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable closeAll = () -> {
            if (closed.compareAndSet(false, true)) {
                pusher.shutdown();
                Disposable up = upstreamRef.get();
                if (up != null) {
                    up.dispose();
                }
            }
        };

        try {
            Disposable upstream = zhipuWebClient.post()
                    .uri("/chat/completions")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            data -> {
                                StreamChunk chunk = StreamUsageParser.parse(data);
                                if (chunk.done()) {
                                    return; // [DONE] 终止标记无计费信息，无需透传
                                }
                                if (chunk.hasUsage()) {
                                    captured.set(chunk.usage());
                                }
                                if (chunk.deltaContent() != null) {
                                    received.append(chunk.deltaContent());
                                }
                                // 16.2：event-loop 只提交不推送，阻塞写发生在专属虚拟线程
                                pusher.submit(() -> {
                                    try {
                                        emitter.send(SseEmitter.event().data(data));
                                    } catch (Exception e) {
                                        // 下游断开/写失败（推送线程内）：终结连接 + 结算 + 取消上游
                                        log.warn("下游推送失败: requestId={}, {}", requestId, e.getMessage());
                                        finalizeStreamBilling(appId, userId, requestId, requestedModel,
                                                request, captured.get(), received.toString(), preCost, settled);
                                        emitter.completeWithError(e);
                                        closeAll.run();
                                    }
                                });
                            },
                            err -> {
                                log.error("上游流式调用失败: requestId={}, {}", requestId, err.getMessage());
                                finalizeStreamBilling(appId, userId, requestId, requestedModel,
                                        request, captured.get(), received.toString(), preCost, settled);
                                closeAll.run();
                                emitter.completeWithError(err);
                            },
                            () -> {
                                finalizeStreamBilling(appId, userId, requestId, requestedModel,
                                        request, captured.get(), received.toString(), preCost, settled);
                                closeAll.run();
                                log.info("流式对话完成: 耗时={}ms, requestId={}, usageCaptured={}, receivedChars={}",
                                        System.currentTimeMillis() - start, requestId,
                                        captured.get() != null, received.length());
                                emitter.complete();
                            });
            upstreamRef.set(upstream);

            // ③ 下游断连/超时 => 结算 + 关闭推送器 + 反向取消上游订阅（先算账再止损，顺序不可反）
            emitter.onCompletion(closeAll::run);
            emitter.onTimeout(() -> {
                finalizeStreamBilling(appId, userId, requestId, requestedModel,
                        request, captured.get(), received.toString(), preCost, settled);
                closeAll.run();
            });
            emitter.onError(t -> {
                finalizeStreamBilling(appId, userId, requestId, requestedModel,
                        request, captured.get(), received.toString(), preCost, settled);
                closeAll.run();
            });

            return emitter;
        } catch (RuntimeException e) {
            // 订阅建立等同步异常：未产生任何消费 → 预扣全额退还
            if (settled.compareAndSet(false, true)) {
                billingService.settle(userId, preCost, 0L);
            }
            closeAll.run();
            throw e;
        }
    }

    /**
     * 流式结算（三路回调竞态收敛点：完成/出错/超时，AtomicBoolean 保证只执行一次）
     *
     * 精确路径：usage 已捕获 → 精确实扣
     * 估算路径：断连未捕获 usage → promptTokens 按 messages 估算 + completionTokens 按已收文本估算
     * 空流路径：无 usage 且无内容（上游立即失败）→ 无消费，预扣全额退还
     * 结算异常：不向上抛（SSE 已无法通知下游），预扣保留待人工对账并 ERROR 留痕
     */
    private void finalizeStreamBilling(Long appId, Long userId, String requestId, String requestedModel,
                                       ChatRequest request, StreamChunk.Usage usage, String receivedText,
                                       long preCost, AtomicBoolean settled) {
        if (!settled.compareAndSet(false, true)) {
            return; // 已被三路中的另一路结算
        }
        try {
            if (usage == null && receivedText.isEmpty()) {
                billingService.settle(userId, preCost, 0L); // 空流：无消费全退
                log.info("流式空流（无消费）: requestId={}, 预扣全退 preCost={}", requestId, preCost);
                return;
            }

            long promptTokens;
            long completionTokens;
            boolean estimated = usage == null;
            if (usage != null) {
                promptTokens = usage.promptTokens();
                completionTokens = usage.completionTokens();
            } else {
                String promptText = request.getMessages().stream()
                        .map(ChatRequest.Message::getContent)
                        .reduce("", String::concat);
                promptTokens = estimateTokens(promptText);
                completionTokens = estimateTokens(receivedText);
            }

            String messagesJson = CHAT_JSON.writeValueAsString(request.getMessages());
            BillingRecord record = billingService.deduct(appId, userId, requestId,
                    requestedModel, requestedModel, messagesJson,
                    (int) promptTokens, (int) completionTokens);
            billingService.settle(userId, preCost, record.getCost());
            log.info("流式计费完成: requestId={}, prompt={}, completion={}, cost={}, estimated={}",
                    requestId, promptTokens, completionTokens, record.getCost(), estimated);
        } catch (Exception e) {
            // 结算失败：预扣保留（不退），ERROR 留痕待人工对账——比静默丢账安全
            log.error("流式结算异常（预扣保留待对账）: requestId={}, preCost={}, {}",
                    requestId, preCost, e.getMessage());
        }
    }

    /**
     * token 估算（断连兜底）：中文 1 字≈1 token，非 CJK 4 字符≈1 token，取较大者
     * 精度不追求精确——估算语义只在"断连无 usage"时启用，宁可粗账不可无账
     */
    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.codePointAt(i)) == Character.UnicodeScript.HAN) {
                cjk++;
            }
        }
        int nonCjk = text.length() - cjk;
        return Math.max(cjk, nonCjk / 4);
    }
}
