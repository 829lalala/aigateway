package com.aigateway.config;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费者容器配置（阶段 20）：统一消费失败兜底链路
 *
 * 兜底链路：消费者抛异常 → DefaultErrorHandler 本地重试（FixedBackOff 1s 间隔 × 2 次）
 *          → 仍失败 → DeadLetterPublishingRecoverer 转发死信 topic（{@code <原topic>.DLT}）
 *
 * 为什么不用 @RetryableTopic（阶段 17.3 踩坑实录）：
 *   Spring Framework 7 内置 ASM 读注解默认值（含数组成员）抛 IllegalArgumentException，
 *   组件扫描直接炸启动——编程式配置绕开注解元数据，行为等价且显式可见
 *
 * 语义分层（与 SummaryConsumer 的业务级 RetryTemplate 不冲突）：
 *   - DefaultErrorHandler 是"容器级"兜底：只兜冒泡到框架的异常（GiftCode 路径）
 *   - Summary 路径在业务层 catch 了异常自己重试（RetryTemplate），不会冒泡到这里；
 *     它的 DLT 是重试耗尽后显式发送的存档消息（业务终态先落库，死信只是事后追溯）
 *
 * 死信路由细节：DeadLetterPublishingRecoverer 默认目标 = 原 topic 名 + ".DLT"，
 * 分区沿用原消息的分区号（同分区保序，排查时可按原分区对应）
 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * 错误处理器独立成 Bean：可单独注入/测试（重试 → 死信兜底链）
     * FixedBackOff(间隔, 重试次数)：首次尝试不计入，共 1 次原始 + 2 次重试；
     * 重试在 listener 线程内阻塞执行（每次间隔 1s）——总量可控，不设非阻塞重试 topic
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        return new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate),
                new FixedBackOff(1000L, 2L));
    }

    /**
     * 覆盖 Boot 自动配置的监听容器工厂：借用 Configurer 保留默认配置（并发数、
     * application.properties 里的消费者属性），只替换错误处理器。
     * 泛型用 Object,Object 对齐 Configurer.configure 的签名（Boot 默认 factory 同款）；
     * @KafkaListener 方法的 String 参数由消息转换器在运行时解析，不受影响
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
