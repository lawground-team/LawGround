package com.lawground.job.config;

import com.lawground.job.service.LiveRunPlan;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "lawground.analysis.live", name = "enabled", havingValue = "true")
@EnableScheduling
public class LiveQueueConfiguration {
    public static final String QUEUE = "lawground.analysis.live.v1", KEY = "analysis.live";

    @Bean
    public InitializingBean liveAnalysisGuard(
            @Value("${lawground.analysis.mock-enabled:false}") boolean mock, LiveRunPlan plan) {
        return () -> {
            if (mock)
                throw new IllegalStateException("Mock and LIVE analysis are mutually exclusive");
            plan.validateConfiguration();
        };
    }

    @Bean
    DirectExchange liveAnalysisExchange() {
        return new DirectExchange(MockQueueConfiguration.EXCHANGE, true, false);
    }

    @Bean
    Queue liveAnalysisQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding liveAnalysisBinding(Queue liveAnalysisQueue, DirectExchange liveAnalysisExchange) {
        return BindingBuilder.bind(liveAnalysisQueue).to(liveAnalysisExchange).with(KEY);
    }
}
