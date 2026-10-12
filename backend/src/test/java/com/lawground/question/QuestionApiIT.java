package com.lawground.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class QuestionApiIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:0.8.2-pg16")
                            .asCompatibleSubstituteFor("postgres"));

    @Container @ServiceConnection
    static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.2.9-management");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    private UUID owner;
    private UUID other;

    @BeforeEach
    void members() {
        owner = UUID.randomUUID();
        other = UUID.randomUUID();
        for (UUID id : new UUID[] {owner, other})
            jdbc.update(
                    "INSERT INTO members(id,display_name,status) VALUES (?,'Synthetic member','ACTIVE')",
                    id);
    }

    private ObjectNode input() {
        var body = mapper.createObjectNode();
        body.put("subject", "BROKER_LAW")
                .put("stem", "[합성] 옳은 설명은?")
                .put("questionType", "SELECT_CORRECT")
                .put("providedAnswerNumber", 2)
                .put("referenceDate", "2025-10-25");
        var choices = body.putArray("choices");
        for (int n = 1; n <= 5; n++) choices.addObject().put("number", n).put("text", "합성 선지 " + n);
        return body;
    }

    private JsonNode create(ObjectNode body, UUID member) throws Exception {
        var result =
                mvc.perform(
                                post("/api/v1/questions")
                                        .with(user(member.toString()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsBytes(body)))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("ETag", "\"1\""))
                        .andExpect(jsonPath("$.ownerId").doesNotExist())
                        .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                        .andExpect(jsonPath("$.answerSource").value("USER_PROVIDED"))
                        .andReturn();
        JsonNode response = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/questions/" + response.get("id").asText());
        return response;
    }

    @Test
    void createAndReadPersistFiveChoicesAndReturnUtc() throws Exception {
        JsonNode created = create(input(), owner);
        String id = created.get("id").asText();
        var read =
                mvc.perform(get("/api/v1/questions/" + id).with(user(owner.toString())))
                        .andExpect(status().isOk())
                        .andExpect(header().string("ETag", "\"1\""))
                        .andExpect(jsonPath("$.choices.length()").value(5))
                        .andReturn();
        assertThat(mapper.readTree(read.getResponse().getContentAsByteArray())).isEqualTo(created);
        assertThat(created.get("createdAt").asText()).endsWith("Z");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_choices WHERE question_id = ?",
                                Integer.class,
                                UUID.fromString(id)))
                .isEqualTo(5);
    }

    @Test
    void preservesNegationNormalizesLinesAndSortsChoices() throws Exception {
        var body = input();
        body.put("questionType", "SELECT_INCORRECT").put("stem", "  옳지 않은 것은?\r\n조건  A\r예외  B  ");
        var choices = new ArrayList<JsonNode>();
        body.withArray("choices").forEach(choices::add);
        Collections.reverse(choices);
        body.set("choices", mapper.valueToTree(choices));
        ((ObjectNode) body.withArray("choices").get(0)).put("text", "  마지막\r\n선지  ");
        var saved = create(body, owner);
        assertThat(saved.get("stem").asText()).isEqualTo("옳지 않은 것은?\n조건  A\n예외  B");
        assertThat(saved.get("choices").get(0).get("number").asInt()).isEqualTo(1);
        assertThat(saved.get("choices").get(4).get("text").asText()).isEqualTo("마지막\n선지");
    }

    @Test
    void unicodeLengthUsesCodePointsRatherThanUtf16() throws Exception {
        var body = input();
        ((ObjectNode) body.withArray("choices").get(0)).put("text", "😀".repeat(2000));
        assertThat(create(body, owner).get("choices").get(0).get("text").asText())
                .isEqualTo("😀".repeat(2000));
        ((ObjectNode) body.withArray("choices").get(0)).put("text", "😀".repeat(2001));
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user(owner.toString()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidInputsDoNotCreatePartialRows() throws Exception {
        var cases = new ArrayList<ObjectNode>();
        for (String key :
                new String[] {
                    "subject",
                    "stem",
                    "questionType",
                    "choices",
                    "providedAnswerNumber",
                    "referenceDate"
                }) {
            var missing = input();
            missing.remove(key);
            cases.add(missing);
            var nullValue = input();
            nullValue.putNull(key);
            cases.add(nullValue);
        }
        for (String bad : new String[] {"", "  \n\t", "\u00a0", "\u0000", "\ud800"}) {
            var body = input();
            body.put("stem", bad);
            cases.add(body);
        }
        for (int number : new int[] {0, 6}) {
            var body = input();
            body.put("providedAnswerNumber", number);
            cases.add(body);
        }
        var duplicate = input();
        ((ObjectNode) duplicate.withArray("choices").get(4)).put("number", 1);
        cases.add(duplicate);
        var few = input();
        few.withArray("choices").remove(4);
        cases.add(few);
        var many = input();
        many.withArray("choices").addObject().put("number", 6).put("text", "합성");
        cases.add(many);
        var nullChoice = input();
        nullChoice.withArray("choices").set(0, mapper.nullNode());
        cases.add(nullChoice);
        var emptyChoice = input();
        ((ObjectNode) emptyChoice.withArray("choices").get(0)).put("text", " ");
        cases.add(emptyChoice);
        var longStem = input();
        longStem.put("stem", "가".repeat(10001));
        cases.add(longStem);
        var badDate = input();
        badDate.put("referenceDate", "2025-02-30");
        cases.add(badDate);
        var arrayDate = input();
        arrayDate.putArray("referenceDate").add(2025).add(10).add(25);
        cases.add(arrayDate);
        var dateTime = input();
        dateTime.put("referenceDate", "2025-10-25T00:00:00Z");
        cases.add(dateTime);
        var unknown = input();
        unknown.put("ownerId", other.toString());
        cases.add(unknown);
        var visibility = input();
        visibility.put("visibility", "PUBLIC");
        cases.add(visibility);
        var stringNumber = input();
        stringNumber.put("providedAnswerNumber", "2");
        cases.add(stringNumber);
        var floatNumber = input();
        floatNumber.put("providedAnswerNumber", 2.5);
        cases.add(floatNumber);
        var numberStem = input();
        numberStem.put("stem", 123);
        cases.add(numberStem);
        var numericEnum = input();
        numericEnum.put("questionType", 0);
        cases.add(numericEnum);
        var unknownChoice = input();
        ((ObjectNode) unknownChoice.withArray("choices").get(0)).put("ownerId", other.toString());
        cases.add(unknownChoice);
        for (var body : cases) {
            mvc.perform(
                            post("/api/v1/questions")
                                    .with(user(owner.toString()))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].rejectedValue").doesNotExist());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM questions WHERE owner_id=?",
                                Integer.class,
                                owner))
                .isZero();
    }

    @Test
    void duplicateJsonKeysAndTrailingJsonAreMalformed() throws Exception {
        String valid = mapper.writeValueAsString(input());
        for (String body :
                new String[] {"{\"stem\":\"one\",\"stem\":\"two\"}", valid + " {}", "{"}) {
            mvc.perform(
                            post("/api/v1/questions")
                                    .with(user(owner.toString()))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    @Test
    void duplicateIsPerOwnerAndDeletedContentCanBeRegisteredAgain() throws Exception {
        var saved = create(input(), owner);
        var normalized = input();
        normalized.put("stem", "  [합성] 옳은 설명은?\r\n  ");
        var reversed = new ArrayList<JsonNode>();
        normalized.withArray("choices").forEach(reversed::add);
        Collections.reverse(reversed);
        normalized.set("choices", mapper.valueToTree(reversed));
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user(owner.toString()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(normalized)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_QUESTION"));
        create(input(), other);
        jdbc.update(
                "UPDATE questions SET deleted_at=now() WHERE id=?",
                UUID.fromString(saved.get("id").asText()));
        create(input(), owner);
    }

    @Test
    void privateDeletedAndMissingQuestionsHaveSame404() throws Exception {
        String id = create(input(), owner).get("id").asText();
        for (String path : new String[] {id, UUID.randomUUID().toString()}) {
            mvc.perform(get("/api/v1/questions/" + path).with(user(other.toString())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/questions/" + id)).andExpect(status().isNotFound());
        jdbc.update("UPDATE questions SET deleted_at=now() WHERE id=?", UUID.fromString(id));
        mvc.perform(get("/api/v1/questions/" + id).with(user(owner.toString())))
                .andExpect(status().isNotFound());
    }

    @Test
    void verifiedPublicQuestionCanBeReadAnonymously() throws Exception {
        String id = create(input(), owner).get("id").asText();
        jdbc.update(
                "UPDATE questions SET source_kind='EXAM_IMPORT', source_key=?, source_url='https://example.invalid/synthetic', answer_source='OFFICIAL_VERIFIED', visibility='PUBLIC' WHERE id=?",
                "synthetic:" + id,
                UUID.fromString(id));
        mvc.perform(get("/api/v1/questions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void authenticationCsrfAndClientIdentityCannotBeBypassed() throws Exception {
        String body = mapper.writeValueAsString(input());
        mvc.perform(
                        post("/api/v1/questions")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user(owner.toString()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user(UUID.randomUUID().toString()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user("not-a-member-id"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user(owner.toString()))
                                .with(csrf())
                                .header("X-Member-Id", other)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE members SET status='DEACTIVATED' WHERE id=?", owner);
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user(owner.toString()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dev/csrf").with(user(other.toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void oversizedBodyReturns413WithoutEchoingInput() throws Exception {
        mvc.perform(
                        post("/api/v1/questions")
                                .with(user(owner.toString()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("x".repeat(128 * 1024 + 1))
                                .header("X-Request-Id", "large-body-fixture"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.requestId").value("large-body-fixture"));
    }

    @Test
    void invalidUuidIsRejectedAndAuthenticatedLibraryIsAvailable() throws Exception {
        for (String id : new String[] {"bad", "1-1-1-1-1"})
            mvc.perform(get("/api/v1/questions/" + id)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/questions").with(user(owner.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void defaultAnalysisDoesNotSilentlyRunMockOrCreateJobs() throws Exception {
        UUID id = UUID.fromString(create(input(), owner).path("id").asText());
        mvc.perform(
                        post("/api/v1/questions/" + id + "/trace-runs")
                                .with(user(owner.toString()))
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"questionRevision\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REQUEST_FAILED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM trace_runs WHERE question_id=?",
                                Integer.class,
                                id))
                .isZero();
    }

    @Test
    void concurrentDuplicatesReturnOne201AndOne409() throws Exception {
        String body = mapper.writeValueAsString(input());
        var start = new CountDownLatch(1);
        Callable<Integer> submit =
                () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return mvc.perform(
                                    post("/api/v1/questions")
                                            .with(user(owner.toString()))
                                            .with(csrf())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(body))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(submit);
            var second = pool.submit(submit);
            start.countDown();
            assertThat(
                            java.util.List.of(
                                    first.get(20, TimeUnit.SECONDS),
                                    second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM questions WHERE owner_id=?",
                                Integer.class,
                                owner))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_choices c JOIN questions q ON q.id=c.question_id WHERE q.owner_id=?",
                                Integer.class,
                                owner))
                .isEqualTo(5);
    }
}
