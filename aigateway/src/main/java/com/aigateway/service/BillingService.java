package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.config.BillingProperties;
import com.aigateway.config.LlmRouteProperties;
import com.aigateway.entity.BillingRecord;
import com.aigateway.entity.User;
import com.aigateway.mapper.BillingRecordMapper;
import com.aigateway.mapper.UserMapper;
import com.aigateway.util.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.LocalDateTime;

/**
 * 计费业务层：余额预检、原子扣费、幂等判重（阶段 5 核心）
 *
 * 三种资损事故与本类的对应防御：
 *   超扣   -> deductBalance 手写 SQL：检查+扣减合一条 UPDATE，行锁内原子完成
 *   重复扣 -> uk_request_id 唯一索引兜底 + request_hash 指纹区分重试/冲突
 *   漏账   -> 扣费与流水在同一事务（@Transactional），要么都成要么都败
 *
 * ⚠️ deduct() 必须被其他 Bean 调用（如 ChatService）：@Transactional 靠 AOP 代理织入，
 *    同类自调用（this 方法）会绕过代理导致事务失效（阶段 1 埋的陷阱）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillingRecordMapper billingRecordMapper;
    private final UserMapper userMapper;
    private final ApiKeyGenerator apiKeyGenerator; // 复用其通用 sha256Hex（算请求参数指纹）
    private final StringRedisTemplate stringRedisTemplate;
    private final BillingProperties billingProperties; // 按模型价格表
    private final LlmRouteProperties routeProperties;  // 主备模型链（预扣按链上最贵估算）

    /** Redis 余额热账本 key 前缀：gw:balance:{userId} */
    public static final String BALANCE_KEY_PREFIX = "gw:balance:";

    /** 预扣的 prompt 缓冲 token 数：转发前 prompt 精确值未知，粗估缓冲（结算时多退少补） */
    private static final long PROMPT_BUFFER_TOKENS = 2000;

    /** 客户端未传 max_tokens 时的默认输出上限（OpenAI 协议标准字段） */
    private static final long DEFAULT_MAX_TOKENS = 1024;

    /**
     * 【防超扣·Redis 线】预扣 Lua 脚本
     * Redis 命令执行单线程，脚本执行期间不插入其他命令——"读-判-扣"天然原子，无 TOCTOU
     * 返回值约定：>=0 扣后余额；-1 余额不足；-2 热账本未初始化（调用方回源 MySQL 加载后重试）
     */
    private static final DefaultRedisScript<Long> PRE_DEDUCT_SCRIPT = new DefaultRedisScript<>("""
            local balance = tonumber(redis.call('GET', KEYS[1]))
            if balance == nil then return -2 end
            if balance >= tonumber(ARGV[1]) then
                return redis.call('DECRBY', KEYS[1], ARGV[1])
            else
                return -1
            end
            """, Long.class);

    /**
     * 模型单价：价格表优先，未配置模型回落默认价
     * （阶段 7 从全局单价升级为按模型定价——计费对象是"实际执行的模型"）
     */
    public long priceFor(String model) {
        return billingProperties.getModelPrices().getOrDefault(model,
                billingProperties.getDefaultPricePer1kTokens());
    }

    /**
     * 链上最贵单价：预扣按"最坏情况"保守估算——
     * 实际执行模型只会 ≤ 最贵（路由只会从主往备降级），保证结算只退不补，不变式更简洁
     */
    public long maxChainPrice() {
        return routeProperties.getRouteChain().stream()
                .mapToLong(this::priceFor)
                .max()
                .orElse(billingProperties.getDefaultPricePer1kTokens());
    }

    /**
     * 计价：cost = ceil(total_tokens / 1000) × 该模型单价
     * 整数向上取整技巧 (n + 999) / 1000：无浮点、无 Math.ceil，金额运算绝不用浮点
     *
     * @param model 实际执行（成功返回响应）的模型——计费跟着实际使用走
     */
    public long calcCost(long totalTokens, String model) {
        long billedK = (totalTokens + 999) / 1000;
        return billedK * priceFor(model);
    }

    /** 指纹算法版本：v2 起把 maxTokens 与 stream 纳入指纹（v1 只有 appId|model|messages） */
    private static final String HASH_VERSION = "v2";

    /**
     * 请求参数指纹：同键不同参数 = 客户端 bug/攻击，要能和"真重试"区分开
     *
     * 为什么必须包含 maxTokens 与 stream（v2 修复）：
     *   上游调用发生在 deduct 之前，所以"幂等命中"只阻止扣费、不阻止再次调用模型。
     *   若指纹不含 maxTokens，同一 requestId 用 max_tokens=16 提交一次、再用 4000 重提，
     *   第二次仍会真实调用上游并拿到 4000 token 的响应，却被判为幂等命中而不扣费——
     *   这是一个白嫖窗口（花一份钱拿两份 token）。
     *   同理 stream 决定计费路径（流式断连按文本估算），两条路径必须是不同的业务。
     *
     * 归一化（normalize）：null 的 maxTokens 按默认值算、null 的 stream 按 false 算，
     *   否则"显式传 false"与"不传"会产生两个指纹，把同一次重试误判成冲突。
     */
    public String buildRequestHash(Long appId, String model, Integer maxTokens, Boolean stream,
                                   String messagesJson) {
        long normalizedMaxTokens = maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens;
        boolean normalizedStream = Boolean.TRUE.equals(stream);
        return apiKeyGenerator.sha256Hex(HASH_VERSION + "|" + appId + "|" + model + "|"
                + normalizedMaxTokens + "|" + normalizedStream + "|" + messagesJson);
    }

    /**
     * v1 旧指纹（不含 maxTokens / stream）：仅用于识别升级前的历史流水。
     *
     * 兼容性说明：数据库里已存在的账单是 v1 指纹。若只认 v2，那些历史请求一旦重试
     * 就会被误判为"同键不同业务"返回 409。因此比对时同时接受 v1 与 v2，
     * 但新写入的流水一律用 v2。
     */
    private String buildLegacyRequestHash(Long appId, String model, String messagesJson) {
        return apiKeyGenerator.sha256Hex(appId + "|" + model + "|" + messagesJson);
    }

    /**
     * 指纹比对：优先 v2；不匹配时回落到 v1，兼容升级前的历史流水。
     *
     * @return true = 指纹一致（可幂等命中）；false = 同键不同业务，应拒绝
     */
    private boolean requestHashMatches(String storedHash, String currentHash, String legacyHash) {
        if (Objects.equals(storedHash, currentHash)) {
            return true;
        }
        if (Objects.equals(storedHash, legacyHash)) {
            log.warn("命中 v1 旧指纹（历史流水），建议该请求后续改用 v2 指纹: storedHash 与当前算法不一致");
            return true;
        }
        return false;
    }

    /**
     * 余额预检（转发前调用，体验层拦截：不给欠费用户白嫖上游 token 的机会）
     *
     * 注意：这是非原子检查，只负责"提前友好拒绝"；真正的防线在 deductBalance 的
     * 条件 UPDATE——原则：应用层检查优化体验，数据层兜底保正确
     */
    public void checkBalance(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getBalance() == null || user.getBalance() <= 0) {
            throw new BizException(403, "余额不足，请先充值");
        }
    }

    /**
     * 预扣成本估算：max_tokens（未传用默认）+ prompt 缓冲，按**链上最贵单价**保守计算——
     * 刻意高估：预扣是"保证金"，结算只退不补，多冻结不损用户
     */
    public long estimatePreCost(Integer maxTokens) {
        long outputTokens = maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens;
        long billedK = (outputTokens + PROMPT_BUFFER_TOKENS + 999) / 1000;
        return billedK * maxChainPrice();
    }

    /**
     * 创建处理中账单并冻结金额。
     *
     * 该方法只操作 MySQL，不调用模型和 Redis：数据库事务很短，外部网络调用由上层在事务外执行。
     * 同一请求编号重复提交时返回原账单；参数指纹不同则拒绝，避免一个幂等键代表两笔业务。
     */
    @Transactional
    public BillingRecord createPending(Long appId, Long userId, String requestId,
                                       String requestModel, String messagesJson,
                                       long frozenAmount) {
        if (appId == null || userId == null || requestId == null || requestId.isBlank()) {
            throw new BizException(400, "计费请求身份信息不能为空");
        }
        if (frozenAmount <= 0) {
            throw new BizException(400, "冻结金额必须大于零");
        }

        String requestHash = buildRequestHash(appId, requestModel, null, null, messagesJson);
        String legacyHash = buildLegacyRequestHash(appId, requestModel, messagesJson);
        BillingRecord existing = billingRecordMapper.selectOne(new LambdaQueryWrapper<BillingRecord>()
                .eq(BillingRecord::getAppId, appId)
                .eq(BillingRecord::getRequestId, requestId));
        if (existing != null) {
            if (!requestHashMatches(existing.getRequestHash(), requestHash, legacyHash)) {
                throw new BizException(409, "幂等键冲突：同一 Request-Id 被不同请求使用");
            }
            existing.setIdempotentHit(true);
            return existing;
        }

        int frozen = userMapper.freezeBalance(userId, frozenAmount);
        if (frozen == 0) {
            throw new BizException(403, "可用余额不足，请先充值");
        }

        BillingRecord pending = new BillingRecord();
        pending.setRequestId(requestId);
        pending.setRequestHash(requestHash);
        pending.setAppId(appId);
        pending.setUserId(userId);
        pending.setModel(requestModel);
        pending.setCost(0L);
        pending.setBalanceAfter(null);
        pending.setStatus(BillingRecord.Status.PROCESSING);
        pending.setFrozenAmount(frozenAmount);
        pending.setActualCost(null);
        try {
            billingRecordMapper.insert(pending);
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突会让本事务回滚，刚才的冻结不会遗留；调用方复用请求编号重试即可。
            log.warn("并发创建处理中账单发生幂等冲突: requestId={}", requestId);
            throw new BizException(409, "幂等键冲突，请复用原请求重试");
        }
        pending.setIdempotentHit(false);
        log.info("创建处理中账单并冻结金额: requestId={}, userId={}, frozenAmount={}",
                requestId, userId, frozenAmount);
        return pending;
    }

    /** 结算处理中账单：总余额扣实际费用，同时解除该请求的全部冻结。 */
    @Transactional
    public BillingRecord settlePending(Long recordId, long actualCost) {
        if (actualCost < 0) {
            throw new BizException(400, "实际费用不能为负数");
        }
        BillingRecord record = lockRecord(recordId);
        if (record.getStatus() == BillingRecord.Status.SETTLED) {
            if (!Objects.equals(record.getActualCost(), actualCost)) {
                throw new BizException(409, "账单已结算，不能修改实际费用");
            }
            return record;
        }
        requireProcessing(record);
        long frozen = record.getFrozenAmount();
        if (actualCost > frozen) {
            throw new BizException(409, "实际费用超过冻结金额，请核对账务");
        }
        if (userMapper.settleFrozenBalance(record.getUserId(), frozen, actualCost) != 1) {
            throw new BizException(409, "账户余额或冻结金额异常，请核对账务");
        }
        User user = userMapper.selectById(record.getUserId());
        if (user == null || user.getBalance() == null) {
            throw new BizException(409, "无法取得结算余额，事务回滚");
        }
        record.setCost(actualCost);
        record.setActualCost(actualCost);
        record.setBalanceAfter(user.getBalance());
        record.setSettledTime(LocalDateTime.now());
        record.setStatus(BillingRecord.Status.SETTLED);
        record.setFailureReason(null);
        persistTransition(record);
        return record;
    }

    /** 仅在确认没有消费时调用：解除冻结，不扣总余额。 */
    @Transactional
    public BillingRecord releasePending(Long recordId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BizException(400, "释放原因不能为空且不能超过500个字符");
        }
        BillingRecord record = lockRecord(recordId);
        if (record.getStatus() == BillingRecord.Status.RELEASED) {
            return record;
        }
        requireProcessing(record);
        if (userMapper.releaseFrozenBalance(record.getUserId(), record.getFrozenAmount()) != 1) {
            throw new BizException(409, "账户冻结金额异常，请核对账务");
        }
        record.setCost(0L);
        record.setActualCost(0L);
        record.setStatus(BillingRecord.Status.RELEASED);
        record.setFailureReason(reason);
        persistTransition(record);
        return record;
    }

    /**
     * 消费或结算结果不确定时保留冻结，并持久化待核对状态。
     * 必须由其他服务通过 Spring 代理调用；结算事务退出后再调用，避免持有账单锁时等待新事务。
     * 原因应为安全的业务说明，不得直接传入包含凭证的上游异常全文。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BillingRecord markReconciliationRequired(Long recordId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BizException(400, "核对原因不能为空且不能超过500个字符");
        }
        BillingRecord record = lockRecord(recordId);
        if (record.getStatus() == BillingRecord.Status.SETTLED
                || record.getStatus() == BillingRecord.Status.RELEASED
                || record.getStatus() == BillingRecord.Status.RECONCILIATION_REQUIRED) {
            // 已完成的账单不降级；重复标记不覆盖首次原因，也不改变任何金额。
            return record;
        }
        if (record.getStatus() != BillingRecord.Status.PROCESSING) {
            throw new BizException(409, "账单状态异常，不能标记待核对");
        }
        // 即使冻结字段异常也要保存待核对证据，不能通过退款掩盖异常。
        record.setStatus(BillingRecord.Status.RECONCILIATION_REQUIRED);
        record.setFailureReason(reason);
        persistTransition(record);
        return record;
    }

    private BillingRecord lockRecord(Long recordId) {
        if (recordId == null || recordId <= 0) {
            throw new BizException(400, "账单编号必须为正数");
        }
        BillingRecord record = billingRecordMapper.selectByIdForUpdate(recordId);
        if (record == null) {
            throw new BizException(404, "账单不存在");
        }
        return record;
    }

    private void requireProcessing(BillingRecord record) {
        if (record.getStatus() != BillingRecord.Status.PROCESSING) {
            throw new BizException(409, "账单不是处理中状态，不能结算或释放");
        }
        if (record.getFrozenAmount() == null || record.getFrozenAmount() <= 0) {
            throw new BizException(409, "账单冻结金额异常，请核对账务");
        }
    }

    private void persistTransition(BillingRecord record) {
        if (billingRecordMapper.updateById(record) != 1) {
            // 异常必须穿过事务边界，使余额变化与账单变化一起回滚。
            throw new BizException(409, "账单状态保存失败，事务回滚");
        }
    }

    /**
     * 【转发前】Redis 原子预扣（防"白嫖窗口"的关键）
     *
     * 后扣模式的漏洞：非原子预检下 N 个并发请求全部通过 → 上游 token 全烧 → 只有一笔扣费成功，
     * 平台替用户垫付 N-1 笔。预扣把"检查余额"变成"冻结余额"，不足者在调上游之前就被拒。
     *
     * 自愈设计：热账本不设 TTL（持续维护的账本，非缓存）；key 丢失（首次使用/Redis 重启）时
     * Lua 返回 -2 → 从 MySQL 权威账本重载后重试一次——权威账本永远能重建热账本
     *
     * @return 预扣后的热账本余额
     */
    public long preDeduct(Long userId, long preCost) {
        String key = BALANCE_KEY_PREFIX + userId;
        for (int attempt = 0; attempt < 2; attempt++) {
            Long result = stringRedisTemplate.execute(PRE_DEDUCT_SCRIPT, List.of(key), String.valueOf(preCost));
            if (result == null) {
                throw new BizException(500, "Redis 预扣异常");
            }
            if (result >= 0) {
                return result; // 预扣成功
            }
            if (result == -1) {
                throw new BizException(403, "余额不足，请先充值");
            }
            // -2：热账本未初始化 → 权威账本加载后重试（最多一次）
            loadBalanceToCache(userId);
        }
        throw new BizException(500, "余额热账本初始化失败");
    }

    /** 从权威账本（MySQL）加载余额到热账本（热账本的自愈来源） */
    private void loadBalanceToCache(Long userId) {
        User user = userMapper.selectById(userId);
        long balance = user == null || user.getBalance() == null ? 0L : user.getBalance();
        stringRedisTemplate.opsForValue().set(BALANCE_KEY_PREFIX + userId, String.valueOf(balance));
        log.info("余额热账本已从权威账本加载: userId={}, balance={}", userId, balance);
    }

    /**
     * 【转发后】结算：把预扣的"保证金"变成实付，多退少补
     *
     * 一致性不变式（by construction）：Redis 净变动 = -preCost + (preCost - actualCost) = -actualCost，
     * 与 MySQL 权威扣费的净变动恒等——两本账天然对齐，不靠对账碰运气
     * 幂等命中（重试未产生扣费）时 actualCost 按 0 结算 → 预扣全额退回，Redis 归位
     * 补扣（delta<0）可能使热账本短暂偏差——MySQL 是权威，容忍并以对账兜底
     */
    public void settle(Long userId, long preCost, long actualCost) {
        long delta = preCost - actualCost;
        if (delta == 0) {
            return;
        }
        String key = BALANCE_KEY_PREFIX + userId;
        if (delta > 0) {
            stringRedisTemplate.opsForValue().increment(key, delta); // 退回多预扣的部分
        } else {
            stringRedisTemplate.opsForValue().decrement(key, -delta); // 补扣差额
        }
        log.info("余额结算: userId={}, preCost={}, actualCost={}, delta={}", userId, preCost, actualCost, delta);
    }

    /**
     * 原子扣费 + 幂等判重 + 流水落库（同一事务）
     *
     * 两个 model 参数的分工（阶段 7 的关键教训）：
     *   requestModel → 只用于请求参数指纹（路由波动下跨重试稳定，防误判 409）
     *   servedModel  → 用于计价与流水记录（成本真实发生在实际执行的模型上）
     *   ——混用会导致"路由降级后按错误模型计价"（实测 chargedCost 5 vs 应为 1）
     *
     * 执行顺序与防御点：
     *   ① 快路径查幂等键：命中且指纹相同 → 返回首次流水（幂等，不扣费）
     *                                            指纹不同 → 409（同键不同业务）
     *   ② 条件 UPDATE 余额（防超扣）：0 行 → 403 余额不足
     *   ③ 读扣费后余额快照（事务内可见自己的修改）
     *   ④ INSERT 流水：撞 uk_request_id（并发同键）→ 抛 409 → 整个事务回滚
     *      ——包括第②步的扣费也回滚，钱绝不会多扣（存储层兜底的威力）
     *
     * @param requestModel 用户请求的模型（指纹用）
     * @param maxTokens    请求的输出上限（指纹用；null 按默认值归一化）
     * @param stream       是否流式请求（指纹用；null 按 false 归一化）
     * @param servedModel  实际执行成功的模型（计价与流水记录用）
     * @return 计费流水（幂等命中返回首次记录；新扣费返回新记录）
     */
    @Transactional
    public BillingRecord deduct(Long appId, Long userId, String requestId, String requestModel,
                                Integer maxTokens, Boolean stream,
                                String servedModel, String messagesJson,
                                int promptTokens, int completionTokens) {
        String requestHash = buildRequestHash(appId, requestModel, maxTokens, stream, messagesJson);
        String legacyHash = buildLegacyRequestHash(appId, requestModel, messagesJson);
        long cost = calcCost((long) promptTokens + completionTokens, servedModel);

        // ① 快路径：幂等命中直接返回（省一次异常开销；并发漏网由④的唯一索引兜底）
        BillingRecord existing = billingRecordMapper.selectOne(new LambdaQueryWrapper<BillingRecord>()
                .eq(BillingRecord::getRequestId, requestId));
        if (existing != null) {
            if (!requestHashMatches(existing.getRequestHash(), requestHash, legacyHash)) {
                // 同键不同业务：客户端 bug 或攻击，Stripe 同款行为
                throw new BizException(409, "幂等键冲突：同一 Request-Id 被不同请求使用");
            }
            log.info("幂等命中，不重复扣费: requestId={}, 首次记录 id={}", requestId, existing.getId());
            existing.setIdempotentHit(true); // 标记：本次未产生新扣费（结算方据此全额退还预扣）
            return existing;
        }

        // ② 原子条件扣费：影响行数 0 = 余额不足
        int updated = userMapper.deductBalance(userId, cost);
        if (updated == 0) {
            log.warn("扣费失败，余额不足: userId={}, cost={}", userId, cost);
            throw new BizException(403, "余额不足，请先充值");
        }

        // ③ 余额快照：事务内 SELECT 能看到自己第②步的修改（隔离性保证）
        User after = userMapper.selectById(userId);

        // ④ 流水落库（幂等兜底在此）：并发同键时撞唯一索引 → 409 → 全事务回滚（扣费一并回滚）
        BillingRecord record = new BillingRecord();
        record.setRequestId(requestId);
        record.setRequestHash(requestHash);
        record.setAppId(appId);
        record.setUserId(userId);
        record.setModel(servedModel); // 流水记录实际执行的模型（阶段 8 用量统计的数据源）
        record.setPromptTokens(promptTokens);
        record.setCompletionTokens(completionTokens);
        record.setCost(cost);
        record.setBalanceAfter(after == null || after.getBalance() == null ? 0L : after.getBalance());
        try {
            billingRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 异常穿出 @Transactional 边界 → 整个事务回滚（含②的扣费）——绝不多扣
            log.warn("并发同键幂等冲突，事务回滚: requestId={}", requestId);
            throw new BizException(409, "幂等键冲突，请重试");
        }

        log.info("扣费成功: requestId={}, appId={}, cost={}, balanceAfter={}",
                requestId, appId, cost, record.getBalanceAfter());
        record.setIdempotentHit(false); // 新扣费（非幂等命中）
        return record;
    }

    /** 查询某用户的扣费后余额（供接口展示/调试） */
    public Long getBalance(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? null : user.getBalance();
    }

    /** 充值（演示用：真实系统是支付回调驱动；本方法同时用于测试造数） */
    @Transactional
    public void recharge(Long userId, long amount) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        user.setBalance(user.getBalance() + amount);
        userMapper.updateById(user);
        // 权威账本变了 → 删热账本缓存（"删优于更"哲学的账本版：下次预扣自动从 MySQL 重载）
        // 不直接 INCRBY 热账本：怕与预扣/结算的并发变动交叉，删除+重载永远干净
        stringRedisTemplate.delete(BALANCE_KEY_PREFIX + userId);
        log.info("充值成功: userId={}, amount={}, balance={}", userId, amount, user.getBalance());
    }

    /** 按用户查流水（对账/账单页数据源） */
    public Map<String, Object> listMyRecords(Long userId) {
        Map<String, Object> data = new HashMap<>();
        data.put("balance", getBalance(userId));
        data.put("records", billingRecordMapper.selectList(new LambdaQueryWrapper<BillingRecord>()
                .eq(BillingRecord::getUserId, userId)
                .orderByDesc(BillingRecord::getCreateTime)));
        return data;
    }
}
