package com.lawground.job.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "lawground.analysis.verification",
        name = "enabled",
        havingValue = "true")
public class VerificationConfiguration {
    @Bean
    InitializingBean verificationModeGuard(
            LiveAnalysisProperties live,
            @Value("${lawground.analysis.mock-enabled:false}") boolean mock) {
        return () -> {
            if (!live.enabled() || mock)
                throw new IllegalStateException(
                        "Verification requires exclusive LIVE analysis mode");
        };
    }
}
