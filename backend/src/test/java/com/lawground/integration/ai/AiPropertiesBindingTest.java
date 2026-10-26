package com.lawground.integration.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.integration.ai.config.AiProperties;
import com.lawground.integration.ai.dto.AiDtos.IdText;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exercise Spring constructor binding as well as normal boot with absent optional AI settings. */
class AiPropertiesBindingTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(ConfigurationUnderTest.class)
                    .withPropertyValues(
                            "lawground.ai.base-url=http://127.0.0.1:8001/internal/v1",
                            "lawground.ai.connect-timeout=3s",
                            "lawground.ai.timeouts.embed=30s",
                            "lawground.ai.timeouts.tokenize=30s",
                            "lawground.ai.timeouts.hyde=60s",
                            "lawground.ai.timeouts.rerank=60s",
                            "lawground.ai.timeouts.verify=120s",
                            "lawground.ai.timeouts.answer=120s");

    @Test
    void canonicalConstructorBindsAllNewAndExistingSettings() {
        context.withPropertyValues(
                        "lawground.ai.api-key=synthetic-binding-test-token",
                        "lawground.ai.max-response-bytes=2097152",
                        "lawground.ai.embedding-model-revision=" + "a".repeat(40),
                        "lawground.ai.tokenizer-version=kiwipiepy-0.24.0+rules-1",
                        "lawground.ai.reranker-model-revision=" + "b".repeat(40))
                .run(
                        application -> {
                            assertThat(application)
                                    .hasNotFailed()
                                    .hasSingleBean(AiProperties.class)
                                    .hasSingleBean(AiInternalClient.class);
                            AiProperties properties = application.getBean(AiProperties.class);
                            assertThat(properties.connectTimeout())
                                    .isEqualTo(Duration.ofSeconds(3));
                            assertThat(properties.timeouts().verify())
                                    .isEqualTo(Duration.ofSeconds(120));
                            assertThat(properties.maxResponseBytes()).isEqualTo(2097152);
                            assertThat(properties.embeddingModelRevision())
                                    .isEqualTo("a".repeat(40));
                            assertThat(properties.tokenizerVersion())
                                    .isEqualTo("kiwipiepy-0.24.0+rules-1");
                            assertThat(properties.rerankerModelRevision())
                                    .isEqualTo("b".repeat(40));
                            assertThat(properties.toString())
                                    .doesNotContain("synthetic-binding-test-token");
                        });
    }

    @Test
    void absentSecretAndPinsPermitBeanStartupButCallsFailClosed() {
        context.run(
                application -> {
                    assertThat(application)
                            .hasNotFailed()
                            .hasSingleBean(AiProperties.class)
                            .hasSingleBean(AiInternalClient.class);
                    AiProperties properties = application.getBean(AiProperties.class);
                    assertThat(properties.maxResponseBytes()).isEqualTo(4194304);
                    assertThat(properties.apiKey()).isNull();
                    assertThat(properties.embeddingModelRevision()).isNull();
                    assertThat(properties.tokenizerVersion()).isNull();
                    assertThat(properties.rerankerModelRevision()).isNull();
                    AiInternalClient client = application.getBean(AiInternalClient.class);
                    assertThatThrownBy(client::ready)
                            .isInstanceOfSatisfying(
                                    AiClientException.class,
                                    failure ->
                                            assertThat(failure.kind())
                                                    .isEqualTo(
                                                            AiClientException.Kind.CONFIGURATION));
                    assertThatThrownBy(
                                    () ->
                                            client.embed(
                                                    UUID.randomUUID(),
                                                    List.of(
                                                            new IdText(
                                                                    UUID.randomUUID(), "합성 입력"))))
                            .isInstanceOfSatisfying(
                                    AiClientException.class,
                                    failure ->
                                            assertThat(failure.kind())
                                                    .isEqualTo(
                                                            AiClientException.Kind.CONFIGURATION));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class ConfigurationUnderTest {
        @Bean
        AiInternalClient aiInternalClient(AiProperties properties) {
            return new AiInternalClient(properties, new ObjectMapper().findAndRegisterModules());
        }
    }
}
