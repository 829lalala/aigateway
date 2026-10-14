package com.aigateway.service;

import com.aigateway.mapper.GiftCodeMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 秒杀消费者失败分层单元测试（阶段 20 D2/D5）：
 * 坏消息宽容跳过（不冒泡不进死信） / 消费幂等 / 正常领取 / 漂移告警路径
 */
@ExtendWith(MockitoExtension.class)
class GiftCodeConsumerTest {

    @Mock
    private GiftCodeMapper giftCodeMapper;

    @Mock
    private GiftCodeService giftCodeService;

    private GiftCodeConsumer newConsumer() {
        return new GiftCodeConsumer(giftCodeMapper, giftCodeService);
    }

    @Test
    @DisplayName("坏消息：非法 JSON 解析抛异常 → 宽容跳过，不抛出且无任何落库交互")
    void badMessage_invalidJson_skippedQuietly() {
        when(giftCodeService.parseUserId("not-a-json")).thenThrow(new RuntimeException("parse error"));

        assertDoesNotThrow(() -> newConsumer().onSeizeEvent("not-a-json"));
        verifyNoInteractions(giftCodeMapper);
    }

    @Test
    @DisplayName("坏消息：缺 userId 字段（NPE）→ 宽容跳过")
    void badMessage_missingField_skippedQuietly() {
        when(giftCodeService.parseUserId("{\"foo\":1}")).thenThrow(new NullPointerException("userId"));

        assertDoesNotThrow(() -> newConsumer().onSeizeEvent("{\"foo\":1}"));
        verifyNoInteractions(giftCodeMapper);
    }

    @Test
    @DisplayName("坏消息：userId 非法值（0）→ 视为无效，宽容跳过")
    void badMessage_invalidUserId_skippedQuietly() {
        when(giftCodeService.parseUserId("{\"userId\":0}")).thenReturn(0L);

        assertDoesNotThrow(() -> newConsumer().onSeizeEvent("{\"userId\":0}"));
        verifyNoInteractions(giftCodeMapper);
    }

    @Test
    @DisplayName("消费幂等：用户已有礼包码（selectCount>0）→ 跳过，不再认领")
    void duplicateUser_claimSkipped() {
        when(giftCodeService.parseUserId(anyString())).thenReturn(42L);
        when(giftCodeMapper.selectCount(any())).thenReturn(1L);

        newConsumer().onSeizeEvent("{\"userId\":42}");

        verify(giftCodeMapper, never()).claimOne(anyLong());
    }

    @Test
    @DisplayName("正常领取：无已有记录 → 原子认领一次")
    void freshUser_claimExecuted() {
        when(giftCodeService.parseUserId(anyString())).thenReturn(42L);
        when(giftCodeMapper.selectCount(any())).thenReturn(0L);
        when(giftCodeMapper.claimOne(42L)).thenReturn(1);

        newConsumer().onSeizeEvent("{\"userId\":42}");

        verify(giftCodeMapper).claimOne(42L);
    }

    @Test
    @DisplayName("双账本漂移：认领 0 行（Redis 有额度但码池空）→ 不抛异常，error 留痕")
    void driftClaim_zeroRows_noException() {
        when(giftCodeService.parseUserId(anyString())).thenReturn(42L);
        when(giftCodeMapper.selectCount(any())).thenReturn(0L);
        when(giftCodeMapper.claimOne(42L)).thenReturn(0);

        assertDoesNotThrow(() -> newConsumer().onSeizeEvent("{\"userId\":42}"));
    }
}
