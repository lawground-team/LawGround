package com.lawground.job.config;

import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "lawground.analysis.mock-enabled", havingValue = "true")
@EnableScheduling
public class MockQueueConfiguration {
    public static final String EXCHANGE = "lawground.analysis.v1",
            QUEUE = "lawground.analysis.mock.v1",
            KEY = "analysis.mock";

    @Bean
    InitializingBean mockGuard(Environment env) {
        return () -> {
            var profiles = List.of(env.getActiveProfiles());
            if (profiles.contains("prod")
                    || !(profiles.contains("local") || profiles.contains("test")))
                throw new IllegalStateException("Mock analysis is local/test only");
        };
    }

    @Bean
    DirectExchange analysisExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue analysisQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding analysisBinding(Queue analysisQueue, DirectExchange analysisExchange) {
        return BindingBuilder.bind(analysisQueue).to(analysisExchange).with(KEY);
    }
}
