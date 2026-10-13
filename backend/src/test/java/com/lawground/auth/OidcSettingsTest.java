package com.lawground.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

class OidcSettingsTest {
    private MockEnvironment profiles(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void productionRequiresCompleteIsolatedOidc() {
        var valid =
                new OidcSettings(
                        true,
                        "synthetic-id",
                        "synthetic-secret",
                        "https://service.example/login/oauth2/code/google");
        assertThatCode(() -> valid.validate(profiles("prod"), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> valid.validate(profiles("prod"), true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> valid.validate(profiles("prod", "test"), false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new OidcSettings(false, null, null, null)
                                        .validate(profiles("prod"), false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new OidcSettings(true, "id", "", valid.redirectUri())
                                        .validate(profiles("prod"), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyLocalHttpAndExactCallbackAreAccepted() {
        var local = profiles("local");
        for (String invalid :
                new String[] {
                    "http://evil.example/login/oauth2/code/google",
                    "https://service.example/other",
                    "https://service.example/login/oauth2/code/google?returnUrl=x",
                    "https://user@service.example/login/oauth2/code/google",
                    "{baseUrl}/login/oauth2/code/google",
                    "https://service.example/login/oauth2/code/google#fragment"
                })
            assertThatThrownBy(
                            () ->
                                    new OidcSettings(true, "id", "secret", invalid)
                                            .validate(local, false))
                    .isInstanceOf(IllegalStateException.class);
        var settings =
                new OidcSettings(
                        true, "id", "secret", "http://127.0.0.1:8080/login/oauth2/code/google");
        assertThatCode(() -> settings.validate(local, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> settings.validate(profiles("prod"), false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settings.validate(local, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionProviderGuardMatchesPinnedGoogleDefaults() {
        var registration =
                CommonOAuth2Provider.GOOGLE
                        .getBuilder("google")
                        .clientId("id")
                        .clientSecret("synthetic-secret")
                        .build();
        assertThatCode(
                        () ->
                                new OidcLoginSupport(
                                        new InMemoryClientRegistrationRepository(registration),
                                        null,
                                        Clock.systemUTC(),
                                        profiles("prod")))
                .doesNotThrowAnyException();
        var fake =
                CommonOAuth2Provider.GOOGLE
                        .getBuilder("google")
                        .clientId("id")
                        .clientSecret("synthetic-secret")
                        .issuerUri("http://127.0.0.1:9999")
                        .build();
        assertThatThrownBy(
                        () ->
                                new OidcLoginSupport(
                                        new InMemoryClientRegistrationRepository(fake),
                                        null,
                                        Clock.systemUTC(),
                                        profiles("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void displayNameIsLimitedByCodePointAndControlsAreRemoved() {
        assertThat(
                        IdentityService.safeName("😀".repeat(100))
                                .codePointCount(
                                        0, IdentityService.safeName("😀".repeat(100)).length()))
                .isEqualTo(80);
        assertThat(IdentityService.safeName("\n\t")).isEqualTo("LawGround 사용자");
        assertThat(IdentityService.safeName("테스트\n이름")).isEqualTo("테스트이름");
    }

    @Test
    void productionCookieIsSecureAndSettingsStringRedactsCredentials() {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new SessionConfiguration()
                .cookieSerializer(profiles("prod"))
                .writeCookieValue(
                        new org.springframework.session.web.http.CookieSerializer.CookieValue(
                                new org.springframework.mock.web.MockHttpServletRequest(),
                                response,
                                "synthetic-session"));
        assertThat(response.getHeader("Set-Cookie"))
                .contains("SESSION=", "Path=/", "HttpOnly", "Secure", "SameSite=Lax")
                .doesNotContain("Domain=");
        assertThat(
                        new OidcSettings(
                                        true,
                                        "synthetic-client",
                                        "synthetic-secret",
                                        "https://example.invalid/login/oauth2/code/google")
                                .toString())
                .doesNotContain("synthetic-client", "synthetic-secret", "example.invalid");
    }

    @Test
    void configurationBeanFailsStartupForDisabledProductionOrDualAuth() {
        var runner =
                new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                        .withUserConfiguration(OidcConfiguration.class)
                        .withBean(
                                IdentityService.class,
                                () -> org.mockito.Mockito.mock(IdentityService.class))
                        .withBean(Clock.class, Clock::systemUTC);
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("lawground.security.oidc.enabled=false")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseInstanceOf(IllegalStateException.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues(
                        "lawground.security.oidc.enabled=true",
                        "lawground.security.dev-auth-enabled=true",
                        "lawground.security.oidc.client-id=synthetic-id",
                        "lawground.security.oidc.client-secret=synthetic-secret",
                        "lawground.security.oidc.redirect-uri=http://127.0.0.1:8080/login/oauth2/code/google")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseInstanceOf(IllegalStateException.class));
    }
}
