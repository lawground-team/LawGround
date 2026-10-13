package com.lawground.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

public class OidcLoginSupport {
    private final ClientRegistrationRepository registrations;
    private final IdentityService identities;
    private final Clock clock;

    public OidcLoginSupport(
            ClientRegistrationRepository registrations,
            IdentityService identities,
            Clock clock,
            Environment environment) {
        this.registrations = registrations;
        this.identities = identities;
        this.clock = clock;
        var google = registrations.findByRegistrationId("google");
        if (google == null) throw new IllegalStateException("Google registration is required");
        if (environment.matchesProfiles("prod")
                && (!"https://accounts.google.com"
                                .equals(google.getProviderDetails().getIssuerUri())
                        || !"https://accounts.google.com/o/oauth2/v2/auth"
                                .equals(google.getProviderDetails().getAuthorizationUri())
                        || !"https://www.googleapis.com/oauth2/v3/certs"
                                .equals(google.getProviderDetails().getJwkSetUri())
                        || !"https://www.googleapis.com/oauth2/v4/token"
                                .equals(google.getProviderDetails().getTokenUri())))
            throw new IllegalStateException("Production accepts the Google OIDC provider only");
    }

    public void configure(HttpSecurity http) throws Exception {
        http.oauth2Login(
                oidc ->
                        oidc.clientRegistrationRepository(registrations)
                                .authorizedClientRepository(new DiscardingAuthorizedClients())
                                .loginPage("/login")
                                .redirectionEndpoint(
                                        callback -> callback.baseUri("/login/oauth2/code/google"))
                                .successHandler(this::success)
                                .failureHandler(
                                        (request, response, failure) -> fail(request, response)));
    }

    private void success(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                || !"google".equals(oauth.getAuthorizedClientRegistrationId())
                || !(oauth.getPrincipal() instanceof OidcUser oidc)) {
            fail(request, response);
            return;
        }
        java.util.UUID id;
        try {
            id =
                    identities.login(
                            oidc.getIssuer().toString(), oidc.getSubject(), oidc.getFullName());
        } catch (RuntimeException unavailable) {
            // A generic fixed failure location; no provider message, token or profile in logs/URL.
            fail(request, response);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new MemberPrincipal(id, Instant.now(clock)),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        request.getSession().setMaxInactiveInterval(30 * 60);
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(request.getContextPath() + "/library");
    }

    private static void fail(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        new SecurityContextLogoutHandler()
                .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(request.getContextPath() + "/login?error=oauth_failed");
    }

    /** Provider tokens are used for login only, never persisted as an authorized client. */
    private static class DiscardingAuthorizedClients implements OAuth2AuthorizedClientRepository {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String id, Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient client,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {}

        @Override
        public void removeAuthorizedClient(
                String id,
                Authentication principal,
                HttpServletRequest request,
                HttpServletResponse response) {}
    }
}
