package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.AdminOnly;
import com.aigateway.common.UserContext;
import com.aigateway.service.GiftCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 礼包码秒杀接口（JWT 通道：人抢购）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/gift-codes")
public class GiftCodeController {

    private final GiftCodeService giftCodeService;

    /** 导入礼包码：POST /gift-codes/import  {"codes":["A1","A2","A3"]}（管理动作，活动开跑前） */
    @PostMapping("/import")
    @AdminOnly
    public Result<Integer> importCodes(@RequestBody Map<String, List<String>> body) {
        return Result.ok(giftCodeService.importCodes(body.get("codes")));
    }

    /** 秒杀：POST /gift-codes/seize（判重 → Lua 预扣 → 发消息，毫秒级返回结果） */
    @PostMapping("/seize")
    public Result<Map<String, Object>> seize() {
        return Result.ok(giftCodeService.seize(UserContext.getUserId()));
    }

    /** 我领到的礼包码：GET /gift-codes/mine（消费者落库后可查到） */
    @GetMapping("/mine")
    public Result<Object> mine() {
        return Result.ok(giftCodeService.mine(UserContext.getUserId()));
    }
}
