package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.UserContext;
import com.aigateway.service.SignService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 签到接口（JWT 通道：人签到/查月历）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/sign")
public class SignController {

    private final SignService signService;

    /** 今日签到：POST /sign（位图天然幂等，重复签到无害） */
    @PostMapping
    public Result<Map<String, Object>> signToday() {
        return Result.ok(signService.signToday(UserContext.getUserId()));
    }

    /** 月历：GET /sign?month=202609（本月已签日期列表 + 总天数） */
    @GetMapping
    public Result<Map<String, Object>> monthCalendar(
            @RequestParam(required = false) String month) {
        String m = month == null || month.isBlank()
                ? java.time.YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                : month;
        return Result.ok(signService.monthCalendar(UserContext.getUserId(), m));
    }
}
