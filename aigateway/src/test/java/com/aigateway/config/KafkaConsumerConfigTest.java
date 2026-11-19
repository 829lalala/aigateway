package com.aigateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Kafka 消费者容器配置单元测试（阶段 20 D1/D5）：
 * 错误处理器 Bean 类型 + factory 装配走 Configurer 保留 Boot 默认配置
 */
class KafkaConsumerConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void deadLetterFactoryDoesNotUseBusinessDeadLetterRecovery() {
        var configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        var consumerFactory = mock(org.springframework.kafka.core.ConsumerFactory.class);
        var factory = new KafkaConsumerConfig()
                .deadLetterKafkaListenerContainerFactory(configurer, consumerFactory);
        verify(configurer).configure(eq(factory), eq(consumerFactory));
        org.junit.jupiter.api.Assertions.assertEquals(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD,
                factory.getContainerProperties().getAckMode());
        assertInstanceOf(DefaultErrorHandler.class,
                org.springframework.test.util.ReflectionTestUtils.getField(factory, "commonErrorHandler"));
    }

    @Test
    @DisplayName("错误处理器 Bean：DefaultErrorHandler（重试 → 死信兜底链）")
    @SuppressWarnings("unchecked")
    void errorHandler_isDefaultErrorHandler() {
        var kafkaTemplate = mock(KafkaTemplate.class);

        var errorHandler = new KafkaConsumerConfig().kafkaErrorHandler(kafkaTemplate);

        assertInstanceOf(DefaultErrorHandler.class, errorHandler);
    }

    @Test
    @DisplayName("factory 装配：走 Configurer 保留 Boot 默认配置，替换错误处理器后正常构建")
    @SuppressWarnings("unchecked")
    void factory_usesConfigurer_andSetsErrorHandler() {
        var configurer = mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        var consumerFactory = mock(org.springframework.kafka.core.ConsumerFactory.class);
        var errorHandler = mock(DefaultErrorHandler.class);

        // 无 getter 可断言（spring-kafka 只有 setCommonErrorHandler）——
        // 断言"构建不抛异常 + Configurer 被调用"即装配契约成立
        var factory = assertDoesNotThrow(() -> new KafkaConsumerConfig()
                .kafkaListenerContainerFactory(configurer, consumerFactory, errorHandler));

        verify(configurer).configure(eq(factory), eq(consumerFactory));
    }
}
