package com.lawground.integration.ai;

import static org.assertj.core.api.Assertions.*;

import com.lawground.integration.ai.client.*;
import com.lawground.integration.ai.config.VerificationProperties.Pin;
import com.lawground.integration.ai.dto.VerifyDtos.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerificationChoiceTest {
    final UUID chunk = UUID.randomUUID();
    final String source = "합성 근거 😀";
    final Pin pin = new Pin("verify:contract-test", "c".repeat(40), "verify-core-v1");

    Request request() {
        var date = LocalDate.of(2025, 10, 25);
        return new Request(
                UUID.randomUUID(),
                new Snapshot(
                        "BROKER_LAW",
                        "합성 질문",
                        "SELECT_INCORRECT",
                        java.util.stream.IntStream.rangeClosed(1, 5)
                                .mapToObj(i -> new Choice(i, "선지 " + i))
                                .toList(),
                        2,
                        date,
                        1),
                date,
                List.of(
                        new Candidate(
                                chunk,
                                source,
                                new Metadata(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "합성법",
                                        "TEST",
                                        date.minusYears(1),
                                        null))));
    }

    List<ChoiceAnalysis> choices() {
        var items = new ArrayList<ChoiceAnalysis>();
        for (int i = 1; i <= 5; i++)
            items.add(
                    new ChoiceAnalysis(
                            i,
                            i == 2
                                    ? Relation.CONTRADICTS
                                    : i == 3 ? Relation.INSUFFICIENT : Relation.SUPPORTS,
                            "합성 설명",
                            i == 2 ? "합성 정정" : null,
                            List.of(),
                            i == 3 ? List.of() : List.of("e1")));
        return items;
    }

    Response response(List<ChoiceAnalysis> items, String tagVersion) {
        return new Response(
                Verdict.SUPPORTED,
                "TEST",
                "합성 설명",
                List.of(
                        new Evidence(
                                "e1", chunk, source, 0, source.codePointCount(0, source.length()))),
                items,
                tagVersion,
                pin.modelRevision(),
                pin.promptVersion());
    }

    @Test
    void optionalNullAndFiveSupportedRelationsRemainCompatible() {
        var in = request();
        VerificationWire.request(in);
        VerificationWire.response(response(null, null), in, pin);
        VerificationWire.response(response(choices(), null), in, pin);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "empty",
                "missing",
                "duplicate",
                "zero",
                "six",
                "null-choice",
                "relation",
                "blank",
                "tags-null",
                "tags",
                "unknown-key",
                "duplicate-key",
                "refs-null",
                "no-positive-evidence",
                "support-correction",
                "insufficient-correction",
                "long-correction",
                "version"
            })
    void rejectsIncompleteUnsupportedOrUngroundedChoices(String kind) {
        var list = new ArrayList<>(choices());
        var c = list.getFirst();
        String version = null;
        switch (kind) {
            case "empty" -> list.clear();
            case "missing" -> list.removeLast();
            case "duplicate" -> list.set(1, c);
            case "null-choice" -> list.set(0, null);
            case "zero", "six" ->
                    list.set(
                            0,
                            new ChoiceAnalysis(
                                    kind.equals("zero") ? 0 : 6,
                                    c.relation(),
                                    c.explanation(),
                                    null,
                                    c.tags(),
                                    c.evidenceKeys()));
            case "relation" ->
                    list.set(
                            0,
                            new ChoiceAnalysis(
                                    1, null, c.explanation(), null, c.tags(), c.evidenceKeys()));
            case "blank" ->
                    list.set(
                            0,
                            new ChoiceAnalysis(
                                    1, c.relation(), " ", null, c.tags(), c.evidenceKeys()));
            case "tags-null", "tags" ->
                    list.set(
                            0,
                            new ChoiceAnalysis(
                                    1,
                                    c.relation(),
                                    c.explanation(),
                                    null,
                                    kind.equals("tags") ? List.of(new Tag("UNAGREED")) : null,
                                    c.evidenceKeys()));
            case "unknown-key", "duplicate-key", "refs-null", "no-positive-evidence" ->
                    list.set(
                            0,
                            new ChoiceAnalysis(
                                    1,
                                    c.relation(),
                                    c.explanation(),
                                    null,
                                    c.tags(),
                                    switch (kind) {
                                        case "unknown-key" -> List.of("not-e1");
                                        case "duplicate-key" -> List.of("e1", "e1");
                                        case "refs-null" -> null;
                                        default -> List.of();
                                    }));
            case "support-correction", "insufficient-correction", "long-correction" ->
                    list.set(
                            0,
                            new ChoiceAnalysis(
                                    1,
                                    kind.equals("insufficient-correction")
                                            ? Relation.INSUFFICIENT
                                            : kind.equals("long-correction")
                                                    ? Relation.CONTRADICTS
                                                    : c.relation(),
                                    c.explanation(),
                                    kind.equals("long-correction")
                                            ? "x".repeat(2001)
                                            : "unjustified",
                                    c.tags(),
                                    c.evidenceKeys()));
            case "version" -> version = "unagreed-v1";
            default -> throw new AssertionError(kind);
        }
        var out = response(list, version);
        assertThatThrownBy(() -> VerificationWire.response(out, request(), pin))
                .isInstanceOfSatisfying(
                        AiClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(AiClientException.Kind.PROTOCOL));
    }
}
