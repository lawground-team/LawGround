package com.lawground.auth;

import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcSettings.class)
public class OidcConfiguration {
    @Bean
    InitializingBean oidcSettingsGuard(
            OidcSettings settings,
            Environment environment,
            @Value("${lawground.security.dev-auth-enabled:false}") boolean devAuthEnabled) {
        return () -> settings.validate(environment, devAuthEnabled);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "lawground.security.oidc.enabled", havingValue = "true")
    static class Enabled {
        @Bean
        @ConditionalOnMissingBean(ClientRegistrationRepository.class)
        ClientRegistrationRepository googleRegistration(
                OidcSettings settings, Environment environment) {
            settings.validate(environment, false);
            return new InMemoryClientRegistrationRepository(
                    CommonOAuth2Provider.GOOGLE
                            .getBuilder("google")
                            .clientId(settings.clientId())
                            .clientSecret(settings.clientSecret())
                            .redirectUri(settings.redirectUri())
                            .scope("openid", "profile")
                            .build());
        }

        @Bean
        OidcLoginSupport oidcLoginSupport(
                ClientRegistrationRepository registrations,
                IdentityService identities,
                Clock clock,
                Environment environment) {
            return new OidcLoginSupport(registrations, identities, clock, environment);
        }
    }
}
