package com.lawground.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** Test sources only. Synthetic, signed OIDC over real loopback HTTP; never a runtime provider. */
final class TestOidcProvider implements AutoCloseable {
    enum Mode {
        VALID,
        BAD_NONCE,
        MISSING_NONCE,
        WRONG_ISSUER,
        WRONG_AUDIENCE,
        EXPIRED,
        BAD_SIGNATURE,
        CANCEL,
        WRONG_USERINFO_SUB
    }

    private record Grant(String subject, String nonce, Mode mode) {}

    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final ObjectMapper mapper = new ObjectMapper();
    private final RSAKey key;
    private final RSAKey badKey;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Map<String, Grant> accessTokens = new ConcurrentHashMap<>();
    volatile String subject = "synthetic-user-1";
    volatile Mode mode = Mode.VALID;

    TestOidcProvider() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        badKey = new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.createContext(
                "/jwks",
                exchange -> json(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
        server.createContext(
                "/userinfo",
                exchange -> {
                    String bearer = exchange.getRequestHeaders().getFirst("Authorization");
                    Grant grant =
                            accessTokens.get(bearer == null ? "" : bearer.replace("Bearer ", ""));
                    if (grant == null) {
                        json(exchange, 401, Map.of("error", "invalid_token"));
                        return;
                    }
                    json(
                            exchange,
                            200,
                            Map.of(
                                    "sub",
                                    grant.mode == Mode.WRONG_USERINFO_SUB
                                            ? "other-sub"
                                            : grant.subject,
                                    "name",
                                    "합성 로그인 사용자",
                                    "email",
                                    "same-synthetic-email@example.invalid"));
                });
        server.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    ClientRegistration registration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("synthetic-client")
                .clientSecret("synthetic-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/google")
                .scope("openid", "profile")
                .authorizationUri(issuer() + "/authorize")
                .tokenUri(issuer() + "/token")
                .jwkSetUri(issuer() + "/jwks")
                .userInfoUri(issuer() + "/userinfo")
                .userNameAttributeName("sub")
                .issuerUri(issuer())
                .clientName("Synthetic OIDC")
                .build();
    }

    private void authorize(HttpExchange exchange) throws IOException {
        var query = parameters(exchange.getRequestURI().getRawQuery());
        String state = query.get("state");
        String redirect = query.get("redirect_uri");
        if (!"synthetic-client".equals(query.get("client_id"))
                || redirect == null
                || !redirect.startsWith("http://127.0.0.1:")
                || !redirect.endsWith("/login/oauth2/code/google")) {
            json(exchange, 400, Map.of("error", "invalid_request"));
            return;
        }
        String location;
        if (mode == Mode.CANCEL) {
            location = redirect + "?error=access_denied&state=" + encode(state);
        } else {
            String code = UUID.randomUUID().toString();
            grants.put(code, new Grant(subject, query.get("nonce"), mode));
            location = redirect + "?code=" + code + "&state=" + encode(state);
        }
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        String basic =
                "Basic "
                        + java.util.Base64.getEncoder()
                                .encodeToString(
                                        "synthetic-client:synthetic-secret"
                                                .getBytes(StandardCharsets.UTF_8));
        if (!basic.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            json(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        Grant grant =
                grants.remove(
                        parameters(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8))
                                .get("code"));
        if (grant == null) {
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        try {
            Instant now = Instant.now();
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer(
                                    grant.mode == Mode.WRONG_ISSUER
                                            ? issuer() + "/other"
                                            : issuer())
                            .subject(grant.subject)
                            .audience(
                                    grant.mode == Mode.WRONG_AUDIENCE
                                            ? "other-client"
                                            : "synthetic-client")
                            .issueTime(Date.from(now.minusSeconds(1)))
                            .expirationTime(
                                    Date.from(
                                            now.plusSeconds(
                                                    grant.mode == Mode.EXPIRED ? -600 : 300)))
                            .claim("name", "합성 로그인 사용자")
                            .claim("email", "same-synthetic-email@example.invalid");
            if (grant.mode != Mode.MISSING_NONCE)
                claims.claim(
                        "nonce", grant.mode == Mode.BAD_NONCE ? "different-nonce" : grant.nonce);
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                            claims.build());
            jwt.sign(new RSASSASigner(grant.mode == Mode.BAD_SIGNATURE ? badKey : key));
            String access = "synthetic-access-" + UUID.randomUUID();
            accessTokens.put(access, grant);
            json(
                    exchange,
                    200,
                    Map.of(
                            "access_token",
                            access,
                            "token_type",
                            "Bearer",
                            "expires_in",
                            300,
                            "scope",
                            "openid profile",
                            "id_token",
                            jwt.serialize()));
        } catch (JOSEException failure) {
            json(exchange, 500, Map.of("error", "server_error"));
        }
    }

    static Map<String, String> parameters(String raw) {
        var result = new HashMap<String, String>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            String[] entry = pair.split("=", 2);
            result.put(
                    URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                    entry.length == 2 ? URLDecoder.decode(entry[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
