package com.lawground.auth;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

@ConfigurationProperties("lawground.security.oidc")
public record OidcSettings(
        boolean enabled, String clientId, String clientSecret, String redirectUri) {
    @Override
    public String toString() {
        return "OidcSettings[enabled=" + enabled + ", credentials=redacted]";
    }

    public void validate(Environment environment, boolean devAuthEnabled) {
        boolean production = environment.matchesProfiles("prod");
        if (production
                && (!enabled || devAuthEnabled || environment.matchesProfiles("local | test")))
            throw new IllegalStateException(
                    "Production requires OIDC and isolated prod configuration");
        if (!enabled) return;
        if (devAuthEnabled)
            throw new IllegalStateException("OIDC and development authentication are exclusive");
        if (clientId == null
                || clientId.isBlank()
                || clientSecret == null
                || clientSecret.isBlank()
                || redirectUri == null
                || redirectUri.isBlank())
            throw new IllegalStateException("Google OIDC configuration is incomplete");
        URI uri;
        try {
            uri = URI.create(redirectUri);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid OIDC callback configuration");
        }
        boolean localHttp =
                environment.matchesProfiles("(local | test) & !prod")
                        && "http".equals(uri.getScheme())
                        && uri.getHost() != null
                        && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !localHttp)
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !"/login/oauth2/code/google".equals(uri.getPath()))
            throw new IllegalStateException(
                    "OIDC callback requires an approved origin and exact callback path");
    }
}
