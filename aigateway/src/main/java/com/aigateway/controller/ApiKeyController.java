package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.UserContext;
import com.aigateway.dto.ApiKeyIssueRequest;
import com.aigateway.dto.ApiKeyStatusRequest;
import com.aigateway.entity.ApiKey;
import com.aigateway.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API Key 管理接口（JWT 通道：人管理自己的 Key）
 *
 * 全部接口处于 LoginInterceptor 保护下，UserContext.getUserId() 即属主。
 * 与 /chat/** 的 API Key 通道（ApiKeyInterceptor）形成两条互不重叠的鉴权链路
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api-key")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /**
     * 发放 Key：POST /api-key  {"appName":"game-community"}
     * 响应中的 key 是完整明文——一生仅此一次，此后库里只有哈希
     */
    @PostMapping
    public Result<Map<String, Object>> issue(@Valid @RequestBody ApiKeyIssueRequest request) {
        String fullKey = apiKeyService.issueKey(UserContext.getUserId(), request.getAppName());
        return Result.ok(Map.of(
                "key", fullKey,
                "warning", "完整明文仅显示这一次，请立即保存到安全位置"
        ));
    }

    /** 我的 Key 列表：GET /api-key（只见 keyPrefix，永无哈希与明文——实体的 @JsonIgnore 兜底） */
    @GetMapping
    public Result<List<ApiKey>> list() {
        return Result.ok(apiKeyService.listMyKeys(UserContext.getUserId()));
    }

    /** 启停 Key：PUT /api-key/{id}/status  {"enable":false}（禁用即吊销） */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody ApiKeyStatusRequest request) {
        apiKeyService.updateStatus(id, UserContext.getUserId(), request.getEnable());
        return Result.ok(null);
    }
}
