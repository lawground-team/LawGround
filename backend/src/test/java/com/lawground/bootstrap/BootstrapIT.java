package com.lawground.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "lawground.security.allow-local-docs=true",
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BootstrapIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private MockMvc mvc;

    @Test
    void flywayAndVectorExtensionAreAvailableInRealPostgresql() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success = true",
                                Integer.class))
                .isEqualTo(8);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                                String.class))
                .isEqualTo("0.8.2");
        assertThat(jdbc.queryForObject("SELECT '[1,0]'::vector <=> '[0,1]'::vector", Double.class))
                .isEqualTo(1.0);
    }

    @Test
    void postgresqlFullTextSearchIsAvailable() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_tsvector('simple', '중개 보수') @@ plainto_tsquery('simple', '보수')",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void rabbitmqCanRoundTripAMessage() {
        var admin = new RabbitAdmin(rabbitTemplate.getConnectionFactory());
        String queueName = "bootstrap." + UUID.randomUUID();
        admin.declareQueue(new Queue(queueName, false, false, false));
        try {
            rabbitTemplate.convertAndSend("", queueName, "bootstrap-ping");
            assertThat(rabbitTemplate.receiveAndConvert(queueName, 5000))
                    .isEqualTo("bootstrap-ping");
        } finally {
            admin.deleteQueue(queueName);
        }
    }

    @Test
    void healthAndReadinessIncludeRealDatabaseAndBroker() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiCanBeGeneratedWithPinnedSpringdocVersion() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("LawGround API"))
                .andExpect(
                        jsonPath("$.paths['/api/v1/me'].get.operationId").value("getCurrentMember"))
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/csrf'].get.operationId")
                                .value("getCsrfToken"))
                .andExpect(
                        jsonPath("$.paths['/api/v1/auth/logout'].post.operationId")
                                .value("logoutCurrentSession"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/auth/logout'].post.parameters[?(@.name == 'X-CSRF-TOKEN')].required")
                                .value(true))
                .andExpect(
                        jsonPath(
                                        "$.paths['/api/v1/questions'].post.parameters[?(@.name == 'X-CSRF-TOKEN')].required")
                                .value(org.hamcrest.Matchers.contains(true)));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
