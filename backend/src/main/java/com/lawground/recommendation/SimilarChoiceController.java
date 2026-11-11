package com.lawground.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.lawground.global.error.ApiException;
import com.lawground.global.error.ErrorCode;
import com.lawground.global.web.ApiParameters;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.integration.ai.client.AiInternalClient;
import com.lawground.trace.dto.TraceDtos;
import com.lawground.trace.service.AnalysisService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * "비슷한 기출": 완료된 분석의 선지 하나와 그 선지의 근거 조문으로 기출 선지 은행(Python)에서 같은 쟁점 기출을 받는다. LLM 없음, 저장하지 않음. 접근
 * 권한·완료 여부는 AnalysisService.result 가 확인한다.
 */
@RestController
@RequestMapping("/api/v1/trace-runs")
public class SimilarChoiceController {
    private final AnalysisService analysis;
    private final AiInternalClient client;

    public SimilarChoiceController(AnalysisService analysis, AiInternalClient client) {
        this.analysis = analysis;
        this.client = client;
    }

    @GetMapping("/{id}/choices/{number}/similar-items")
    public JsonNode similar(@PathVariable String id, @PathVariable String number) {
        if (!number.matches("[1-5]")) throw ApiParameters.invalid();
        int choice = Integer.parseInt(number);
        TraceDtos.Result result = analysis.result(ApiParameters.uuid(id));
        var snapshot = result.inputSnapshot();
        String text = null;
        for (var c : snapshot.path("choices"))
            if (c.path("number").asInt() == choice) text = c.path("text").asText(null);
        if (text == null) throw ApiParameters.invalid();
        Set<UUID> refs = new HashSet<>();
        if (result.choiceAnalyses() != null)
            result.choiceAnalyses().stream()
                    .filter(c -> c.choiceNumber() == choice)
                    .forEach(c -> refs.addAll(c.evidenceIds()));
        var keys = new LinkedHashSet<Map<String, String>>();
        for (var ev : result.evidences())
            if (refs.isEmpty() || refs.contains(ev.evidenceId()))
                keys.add(Map.of("lawTitle", ev.lawTitle(), "articleLabel", ev.articleLabel()));
        UUID requestId = UUID.randomUUID();
        var body =
                Map.of(
                        "requestId",
                        requestId,
                        "subject",
                        snapshot.path("subject").asText(),
                        "text",
                        text,
                        "articleKeys",
                        keys.stream().limit(10).toList(),
                        "limit",
                        3);
        try {
            return client.similarItems(requestId, body);
        } catch (AiClientException e) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.REQUEST_FAILED,
                    "비슷한 기출을 불러오지 못했습니다.");
        }
    }
}
