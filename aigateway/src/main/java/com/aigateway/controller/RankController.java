package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.UserContext;
import com.aigateway.service.RankService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 排行榜接口（JWT 通道：人查看榜单）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/rank")
public class RankController {

    private final RankService rankService;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 签到天数 Top-N：GET /rank/sign?month=202609&top=10 */
    @GetMapping("/sign")
    public Result<List<Map<String, Object>>> topSigners(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "10") int top) {
        String m = month == null || month.isBlank()
                ? YearMonth.now().format(MONTH_FMT) : month;
        return Result.ok(rankService.topSigners(m, top));
    }

    /** 我的名次：GET /rank/sign/me?month=202609（未上榜 rank=null） */
    @GetMapping("/sign/me")
    public Result<Map<String, Object>> myRank(
            @RequestParam(required = false) String month) {
        String m = month == null || month.isBlank()
                ? YearMonth.now().format(MONTH_FMT) : month;
        return Result.ok(rankService.mySignRank(UserContext.getUserId(), m));
    }
}
