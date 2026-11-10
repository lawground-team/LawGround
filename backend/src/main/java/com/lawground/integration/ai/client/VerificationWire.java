package com.lawground.integration.ai.client;

import com.lawground.integration.ai.config.VerificationProperties.Pin;
import com.lawground.integration.ai.dto.VerifyDtos.*;
import com.lawground.law.service.SourceText;
import java.util.*;

/** Shape/provenance checks do not establish semantic or legal correctness. */
public final class VerificationWire {
    /**
     * Agreed 2026-11-09 with the Python verifier: five choice tags, only on CONTRADICTS choices.
     */
    public static final String TAG_SET_VERSION = "choice-tags-v1";

    public static final Set<String> TAG_CODES =
            Set.of("TIMING", "CONDITION", "ACTOR", "CONCEPT", "NUMBER");

    private static final int MAX_TAGS_PER_CHOICE = 2;

    private VerificationWire() {}

    public static void request(Request in) {
        if (in == null
                || in.requestId() == null
                || in.referenceDate() == null
                || in.questionSnapshot() == null
                || in.candidates() == null
                || in.candidates().isEmpty()
                || in.candidates().size() > 5) throw invalid();
        var q = in.questionSnapshot();
        if (!Set.of("BROKER_LAW", "PUBLIC_LAW").contains(q.subject() == null ? "" : q.subject())
                || !Set.of("SELECT_CORRECT", "SELECT_INCORRECT")
                        .contains(q.questionType() == null ? "" : q.questionType())
                || !text(q.stem(), 10000)
                || q.revision() < 1
                || !in.referenceDate().equals(q.referenceDate())
                || q.providedAnswerNumber() < 1
                || q.providedAnswerNumber() > 5
                || q.choices() == null
                || q.choices().size() != 5) throw invalid();
        var numbers = new HashSet<Integer>();
        for (var c : q.choices())
            if (c == null
                    || c.number() < 1
                    || c.number() > 5
                    || !numbers.add(c.number())
                    || !text(c.text(), 2000)) throw invalid();
        var ids = new HashSet<UUID>();
        for (var c : in.candidates()) {
            if (c == null
                    || c.chunkId() == null
                    || !ids.add(c.chunkId())
                    || !text(c.sourceText(), 200000)
                    || c.metadata() == null) throw invalid();
            var m = c.metadata();
            if (m.articleId() == null
                    || m.lawVersionId() == null
                    || !text(m.lawTitle(), 200)
                    || !text(m.articleLabel(), 200)
                    || m.effectiveFrom() == null
                    || m.effectiveFrom().isAfter(in.referenceDate())
                    || m.effectiveTo() != null
                            && (!m.effectiveTo().isAfter(m.effectiveFrom())
                                    || !in.referenceDate().isBefore(m.effectiveTo())))
                throw invalid();
        }
    }

    public static void response(Response out, Request in, Pin pin) {
        if (out == null
                || pin == null
                || out.verdict() == null
                || !text(out.reasonCode(), 80)
                || !text(out.summary(), 10000)
                || out.evidences() == null
                || out.evidences().size() > 50
                || out.verdict() != Verdict.HOLD && out.evidences().isEmpty()) throw protocol();
        if (!pin.modelRevision().equals(out.modelRevision()))
            throw new AiClientException(
                    AiClientException.Kind.VERSION_MISMATCH, 0, "MODEL_REVISION_MISMATCH", false);
        if (!pin.promptVersion().equals(out.promptVersion()))
            throw new AiClientException(
                    AiClientException.Kind.VERSION_MISMATCH, 0, "PROMPT_VERSION_MISMATCH", false);
        if (out.tagSetVersion() != null
                && (!TAG_SET_VERSION.equals(out.tagSetVersion()) || out.choiceAnalyses() == null))
            throw new AiClientException(
                    AiClientException.Kind.PROTOCOL,
                    0,
                    "VERIFICATION_EXTENSION_UNSUPPORTED",
                    false);
        var candidates = new HashMap<UUID, Candidate>();
        in.candidates().forEach(c -> candidates.put(c.chunkId(), c));
        var keys = new HashSet<String>();
        var spans = new HashSet<String>();
        for (var e : out.evidences()) {
            if (e == null
                    || !text(e.evidenceKey(), 80)
                    || !keys.add(e.evidenceKey())
                    || !candidates.containsKey(e.chunkId())
                    || !text(e.quote(), 20000)
                    || !spans.add(e.chunkId() + ":" + e.start() + ":" + e.end())) throw protocol();
            try {
                SourceText.requireQuote(
                        candidates.get(e.chunkId()).sourceText(), e.start(), e.end(), e.quote());
            } catch (IllegalArgumentException bad) {
                throw protocol();
            }
        }
        if (out.choiceAnalyses() == null) return;
        if (out.choiceAnalyses().size() != 5) throw protocol();
        var numbers = new HashSet<Integer>();
        for (var c : out.choiceAnalyses()) {
            if (c == null
                    || c.choiceNumber() < 1
                    || c.choiceNumber() > 5
                    || !numbers.add(c.choiceNumber())
                    || c.relation() == null
                    || !text(c.explanation(), 10000)
                    || c.tags() == null
                    || c.evidenceKeys() == null
                    || c.evidenceKeys().size() > 20) throw protocol();
            if (!c.tags().isEmpty()
                    && (out.tagSetVersion() == null
                            || c.relation() != Relation.CONTRADICTS
                            || c.tags().size() > MAX_TAGS_PER_CHOICE
                            || c.tags().stream()
                                    .anyMatch(t -> t == null || !TAG_CODES.contains(t.code()))
                            || c.tags().stream().map(Tag::code).distinct().count()
                                    != c.tags().size()))
                throw new AiClientException(
                        AiClientException.Kind.PROTOCOL, 0, "VERIFICATION_TAGS_UNSUPPORTED", false);
            var references = new HashSet<String>();
            for (String key : c.evidenceKeys())
                if (!text(key, 80) || !references.add(key) || !keys.contains(key)) throw protocol();
            if (c.relation() != Relation.INSUFFICIENT && references.isEmpty()) throw protocol();
            if (c.correctedText() != null
                    && (c.relation() != Relation.CONTRADICTS
                            || references.isEmpty()
                            || !text(c.correctedText(), 2000))) throw protocol();
        }
    }

    private static boolean text(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.indexOf('\0') < 0
                && value.codePointCount(0, value.length()) <= max;
    }

    private static AiClientException invalid() {
        return new AiClientException(
                AiClientException.Kind.INPUT_INVALID, 0, "INVALID_REQUEST", false);
    }

    private static AiClientException protocol() {
        return new AiClientException(AiClientException.Kind.PROTOCOL, 0, "INVALID_RESPONSE", false);
    }
}
