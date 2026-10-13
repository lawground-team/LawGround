package com.lawground.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1", "lawground.security.oidc.enabled=true",
            "lawground.security.oidc.client-id=synthetic-client",
                    "lawground.security.oidc.client-secret=synthetic-secret",
            "lawground.security.oidc.redirect-uri=http://127.0.0.1:8080/login/oauth2/code/google"
        })
@Import(AuthFlowIT.OidcTestConfiguration.class)
@ActiveProfiles("test")
@Testcontainers
class AuthFlowIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    static final TestOidcProvider provider = provider();

    private static TestOidcProvider provider() {
        try {
            return new TestOidcProvider();
        } catch (Exception unavailable) {
            throw new ExceptionInInitializerError(unavailable);
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OidcTestConfiguration {
        @Bean
        @Primary
        ClientRegistrationRepository testRegistration() {
            return new InMemoryClientRegistrationRepository(provider.registration());
        }

        @Bean
        @Primary
        MutableClock authTestClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        volatile Instant value = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identities;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired PlatformTransactionManager manager;
    @Autowired MutableClock clock;
    private String origin;

    @BeforeEach
    void reset() {
        provider.mode = TestOidcProvider.Mode.VALID;
        provider.subject = "synthetic-" + UUID.randomUUID();
        clock.value = Instant.now();
        origin = "http://127.0.0.1:" + port;
    }

    private static class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        final HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> request(String method, String url, String csrf, String body)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (body != null) builder.header("Content-Type", "application/json");
            return client.send(
                    builder.method(
                                    method,
                                    body == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        String sessionId() {
            String cookie =
                    cookies.getCookieStore().getCookies().stream()
                            .filter(c -> c.getName().equals("SESSION"))
                            .findFirst()
                            .orElseThrow()
                            .getValue()
                            .replace("\"", "");
            return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
        }
    }

    private HttpResponse<String> get(Browser browser, String path) throws Exception {
        return browser.request("GET", origin + path, null, null);
    }

    private String csrf(Browser browser) throws Exception {
        var response = get(browser, "/api/v1/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode json = mapper.readTree(response.body());
        assertThat(json.path("token").asText()).isNotBlank();
        assertThat(json.path("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(json.path("parameterName").asText()).isEqualTo("_csrf");
        assertThat(json.size()).isEqualTo(3);
        return json.path("token").asText();
    }

    private String callback(Browser browser) throws Exception {
        var start = get(browser, "/oauth2/authorization/google?returnUrl=https://example.invalid/");
        assertThat(start.statusCode()).isEqualTo(302);
        String authorization = start.headers().firstValue("Location").orElseThrow();
        assertThat(authorization).startsWith(provider.issuer() + "/authorize?");
        var response = browser.request("GET", authorization, null, null);
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    private String login(Browser browser) throws Exception {
        var response = browser.request("GET", callback(browser), null, null);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue(origin + "/library");
        var me = get(browser, "/api/v1/me");
        assertThat(me.statusCode()).isEqualTo(200);
        capture("Member", me);
        var json = mapper.readTree(me.body());
        assertThat(json.size()).isEqualTo(2);
        return json.path("id").asText();
    }

    private void rejected(Browser browser, String callback) throws Exception {
        var response = browser.request("GET", callback, null, null);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValue(origin + "/login?error=oauth_failed");
        assertThat(get(browser, "/api/v1/me").statusCode()).isEqualTo(401);
    }

    private void capture(String schema, HttpResponse<String> response) throws Exception {
        var directory = Path.of("build/contract-responses");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("auth-" + UUID.randomUUID() + ".json"),
                mapper.writeValueAsString(
                        Map.of("schema", schema, "body", mapper.readTree(response.body()))));
    }

    @Test
    void firstRepeatLoginRotatesSessionAndPersistsOnlyInternalIdentity() throws Exception {
        var browser = new Browser();
        String oldCsrf = csrf(browser), oldId = browser.sessionId();
        var initialCookie = get(browser, "/api/v1/auth/csrf");
        // The initial CSRF response, not every request, issues a session cookie.
        assertThat(initialCookie.statusCode()).isEqualTo(200);
        String id = login(browser);
        assertThat(browser.sessionId()).isNotEqualTo(oldId);
        assertThat(sessions.findById(oldId)).isNull();
        org.springframework.session.Session session = sessions.findById(browser.sessionId());
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        SecurityContext context =
                session.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getName()).isEqualTo(id);
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(MemberPrincipal.class);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        String serialized =
                jdbc.query(
                                "SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id=?",
                                (row, n) ->
                                        new String(row.getBytes(1), StandardCharsets.ISO_8859_1),
                                primaryId(browser.sessionId()))
                        .toString();
        assertThat(serialized)
                .doesNotContain(
                        "OidcIdToken",
                        "OAuth2AccessToken",
                        "OAuth2AuthenticationToken",
                        "same-synthetic-email",
                        provider.subject);
        assertThat(
                        browser.request("POST", origin + "/api/v1/auth/logout", oldCsrf, null)
                                .statusCode())
                .isEqualTo(403);
        assertThat(get(browser, "/api/v1/me").headers().firstValue("Cache-Control"))
                .hasValue("no-store");
        var second = new Browser();
        assertThat(login(second)).isEqualTo(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_identities WHERE issuer=? AND subject=?",
                                Integer.class,
                                provider.issuer(),
                                provider.subject))
                .isEqualTo(1);
    }

    @Test
    void browserCookiesAndJsonUnauthorizedFollowContract() throws Exception {
        var browser = new Browser();
        var response = get(browser, "/api/v1/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie)
                .contains("SESSION=", "Path=/", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=", "Secure");
        var unauthenticated = get(browser, "/api/v1/me");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.headers().firstValue("Location")).isEmpty();
        assertThat(mapper.readTree(unauthenticated.body()).path("code").asText())
                .isEqualTo("AUTHENTICATION_REQUIRED");
        capture("Error", unauthenticated);
    }

    @Test
    void csrfLogoutInvalidatesCurrentSessionAndRejectsOldToken() throws Exception {
        var browser = new Browser();
        String id = login(browser);
        String sessionId = browser.sessionId(), token = csrf(browser);
        assertThat(browser.request("POST", origin + "/api/v1/auth/logout", null, null).statusCode())
                .isEqualTo(403);
        assertThat(get(browser, "/api/v1/auth/logout").statusCode()).isEqualTo(403);
        assertThat(get(browser, "/api/v1/me").statusCode()).isEqualTo(200);
        var logout = browser.request("POST", origin + "/api/v1/auth/logout", token, null);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(logout.headers().allValues("Set-Cookie").toString())
                .contains("SESSION=", "Max-Age=0");
        assertThat(sessions.findById(sessionId)).isNull();
        assertThat(get(browser, "/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(browser.request("POST", origin + "/api/v1/questions", token, "{}").statusCode())
                .isEqualTo(403);
        assertThat(
                        browser.request("POST", origin + "/api/v1/questions", csrf(browser), "{}")
                                .statusCode())
                .isEqualTo(401);
        assertThat(login(browser)).isEqualTo(id);
    }

    @Test
    void invalidSignedTokensAndProviderCancelNeverCreateIdentity() throws Exception {
        for (var mode : TestOidcProvider.Mode.values()) {
            if (mode == TestOidcProvider.Mode.VALID) continue;
            provider.mode = mode;
            var browser = new Browser();
            rejected(browser, callback(browser));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM auth_identities WHERE issuer=? AND subject=?",
                                    Integer.class,
                                    provider.issuer(),
                                    provider.subject))
                    .as(mode.name())
                    .isZero();
        }
    }

    @Test
    void mismatchedStateOtherBrowserAndCodeReplayAreRejected() throws Exception {
        var browser = new Browser();
        String original = callback(browser);
        rejected(browser, original.replaceAll("state=[^&]+", "state=wrong-state"));
        var first = new Browser();
        String pending = callback(first);
        rejected(new Browser(), pending);
        var accepted = first.request("GET", pending, null, null);
        assertThat(accepted.headers().firstValue("Location")).hasValue(origin + "/library");
        // The saved authorization request and the provider authorization code are one-use.
        rejected(first, pending);
    }

    @Test
    void inactiveAccountIsDeniedAtLoginAndExistingSession() throws Exception {
        var browser = new Browser();
        String id = login(browser), sessionId = browser.sessionId();
        jdbc.update("UPDATE members SET status='DEACTIVATED' WHERE id=?", UUID.fromString(id));
        var blocked = get(browser, "/api/v1/me");
        assertThat(blocked.statusCode()).isEqualTo(403);
        capture("Error", blocked);
        assertThat(sessions.findById(sessionId)).isNull();
        var retry = new Browser();
        rejected(retry, callback(retry));
        assertThatThrownBy(() -> identities.login(provider.issuer(), provider.subject, "Synthetic"))
                .isInstanceOf(DisabledException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_identities WHERE issuer=? AND subject=?",
                                Integer.class,
                                provider.issuer(),
                                provider.subject))
                .isEqualTo(1);
    }

    @Test
    void absoluteTwelveHourExpiryIsEnforcedEvenWhenJdbcIdleIsFresh() throws Exception {
        var browser = new Browser();
        login(browser);
        String sessionId = browser.sessionId();
        clock.value = clock.value.plus(Duration.ofHours(12)).minusSeconds(1);
        assertThat(get(browser, "/api/v1/me").statusCode()).isEqualTo(200);
        clock.value = clock.value.plusSeconds(1);
        assertThat(get(browser, "/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(sessions.findById(sessionId)).isNull();
        assertThat(csrf(browser)).isNotBlank();
    }

    @Test
    void jdbcIdleExpiryCleanupAndIndependentRepositoryRestore() throws Exception {
        var browser = new Browser();
        String id = login(browser), sessionId = browser.sessionId();
        var restored = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(manager));
        org.springframework.session.Session session = restored.findById(sessionId);
        String primaryId = primaryId(sessionId);
        assertThat(session).isNotNull();
        SecurityContext context =
                session.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getName()).isEqualTo(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT principal_name FROM spring_session WHERE session_id=?",
                                String.class,
                                sessionId))
                .isEqualTo(id);
        jdbc.update(
                "UPDATE spring_session SET last_access_time=?,expiry_time=? WHERE session_id=?",
                System.currentTimeMillis() - Duration.ofMinutes(31).toMillis(),
                System.currentTimeMillis() - 1,
                sessionId);
        assertThat(get(browser, "/api/v1/me").statusCode()).isEqualTo(401);
        sessions.cleanUpExpiredSessions();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM spring_session WHERE session_id=?",
                                Integer.class,
                                sessionId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM spring_session_attributes WHERE session_primary_id=?",
                                Integer.class,
                                primaryId))
                .isZero();
    }

    private String primaryId(String sessionId) {
        return jdbc.queryForObject(
                "SELECT primary_id FROM spring_session WHERE session_id=?",
                String.class,
                sessionId);
    }

    @Test
    void concurrentFirstLoginCreatesOneMemberWithoutOrphans() throws Exception {
        String subject = provider.subject;
        int before = jdbc.queryForObject("SELECT count(*) FROM members", Integer.class);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<java.util.concurrent.Future<UUID>>();
            for (int i = 0; i < 6; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return identities.login(provider.issuer(), subject, "동시 합성 회원");
                                }));
            start.countDown();
            var ids = new java.util.HashSet<UUID>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM members", Integer.class))
                .isEqualTo(before + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_identities WHERE issuer=? AND subject=?",
                                Integer.class,
                                provider.issuer(),
                                subject))
                .isEqualTo(1);
    }

    @Test
    void sameEmailDifferentSubjectDoesNotMergeAccounts() throws Exception {
        String first = login(new Browser());
        provider.subject = "other-" + provider.subject;
        String second = login(new Browser());
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void logoutOnlyEndsCurrentBrowserSession() throws Exception {
        var first = new Browser();
        var second = new Browser();
        assertThat(login(first)).isEqualTo(login(second));
        String firstSession = first.sessionId(), secondSession = second.sessionId();
        assertThat(firstSession).isNotEqualTo(secondSession);
        assertThat(
                        first.request("POST", origin + "/api/v1/auth/logout", csrf(first), null)
                                .statusCode())
                .isEqualTo(204);
        assertThat(get(first, "/api/v1/me").statusCode()).isEqualTo(401);
        assertThat(get(second, "/api/v1/me").statusCode()).isEqualTo(200);
        assertThat(sessions.findById(firstSession)).isNull();
        assertThat(sessions.findById(secondSession)).isNotNull();
    }

    @Test
    void realOidcSessionProtectsQuestionOwnershipAcrossRelogin() throws Exception {
        var owner = new Browser();
        String subject = provider.subject, member = login(owner);
        var input =
                mapper.createObjectNode()
                        .put("subject", "BROKER_LAW")
                        .put("stem", "[합성 인증 검사] 옳은 설명은?")
                        .put("questionType", "SELECT_CORRECT")
                        .put("providedAnswerNumber", 2)
                        .put("referenceDate", "2025-10-25");
        var choices = input.putArray("choices");
        for (int n = 1; n <= 5; n++) choices.addObject().put("number", n).put("text", "합성 선지 " + n);
        var created =
                owner.request("POST", origin + "/api/v1/questions", csrf(owner), input.toString());
        assertThat(created.statusCode()).isEqualTo(201);
        capture("Question", created);
        String question = mapper.readTree(created.body()).path("id").asText();
        provider.subject = "other-" + subject;
        var other = new Browser();
        assertThat(login(other)).isNotEqualTo(member);
        assertThat(get(other, "/api/v1/questions/" + question).statusCode()).isEqualTo(404);
        assertThat(mapper.readTree(get(other, "/api/v1/questions?scope=mine").body()).path("items"))
                .isEmpty();
        assertThat(
                        owner.request("POST", origin + "/api/v1/auth/logout", csrf(owner), null)
                                .statusCode())
                .isEqualTo(204);
        provider.subject = subject;
        assertThat(login(owner)).isEqualTo(member);
        assertThat(get(owner, "/api/v1/questions/" + question).statusCode()).isEqualTo(200);
        assertThat(
                        mapper.readTree(get(owner, "/api/v1/questions?scope=mine").body())
                                .path("items")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo(question);
    }

    @Test
    void officialSessionSchemaMatchesResolvedArtifactAndUpgradesV005() throws Exception {
        try (var resource =
                getClass()
                        .getResourceAsStream(
                                "/org/springframework/session/jdbc/schema-postgresql.sql")) {
            assertThat(resource).isNotNull();
            String official =
                    new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("\r\n", "\n")
                            .strip();
            String migration =
                    Files.readString(
                                    Path.of(
                                            "src/main/resources/db/migration/V006__oidc_and_jdbc_sessions.sql"))
                            .replace("\r\n", "\n")
                            .strip();
            assertThat(migration).endsWith(official);
        }
        var source =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl()
                                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                                + "currentSchema=auth_upgrade",
                        postgres.getUsername(),
                        postgres.getPassword());
        var flyway = Flyway.configure().dataSource(source).defaultSchema("auth_upgrade");
        flyway.target("5").load().migrate();
        var sql = new JdbcTemplate(source);
        var checksums =
                sql.queryForList(
                        "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank");
        Flyway.configure().dataSource(source).defaultSchema("auth_upgrade").load().migrate();
        assertThat(
                        sql.queryForList(
                                "SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND version<>'006' AND version<>'6' ORDER BY installed_rank"))
                .isEqualTo(checksums);
        assertThat(sql.queryForObject("SELECT count(*) FROM auth_identities", Integer.class))
                .isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM spring_session", Integer.class))
                .isZero();
    }

    @Test
    void realHttpServesFrontendDeepLinksAndAssetsWithoutExposingPrivateFiles() throws Exception {
        var browser = new Browser();
        for (String path :
                new String[] {
                    "/",
                    "/login",
                    "/library",
                    "/questions/new",
                    "/questions/00000000-0000-4000-8000-000000000001",
                    "/runs/00000000-0000-4000-8000-000000000001"
                }) {
            var response = get(browser, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).contains("<html lang=\"ko\">", "/app/app.js");
            assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
                    .contains("script-src 'self'", "frame-ancestors 'none'");
        }
        for (String path :
                new String[] {
                    "/app/app.js",
                    "/app/api.js",
                    "/app/domain.js",
                    "/app/styles.css",
                    "/app/favicon.svg"
                }) assertThat(get(browser, path).statusCode()).as(path).isEqualTo(200);
        for (String path :
                new String[] {"/.env", "/AGENTS.md", "/AI/STATUS.md", "/frontend/src/app.js"})
            assertThat(get(browser, path).statusCode()).as(path).isNotEqualTo(200);
        assertThat(get(browser, "/api/v1/questions?scope=mine").statusCode()).isEqualTo(401);
    }

    @Test
    void publicUiConfigurationContainsOnlyCapabilityFlags() throws Exception {
        var config = get(new Browser(), "/api/v1/app/config");
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(config.headers().firstValue("Cache-Control")).hasValue("no-store");
        var json = mapper.readTree(config.body());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.path("googleLoginEnabled").asBoolean()).isTrue();
        assertThat(json.path("analysisAvailable").asBoolean()).isFalse();
        assertThat(json.path("localEnvironment").asBoolean()).isFalse();
    }
}
