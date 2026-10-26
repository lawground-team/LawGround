package com.lawground.trace.controller;

import com.lawground.global.web.ApiParameters;
import com.lawground.global.web.PageResponse;
import com.lawground.trace.dto.TraceDtos;
import com.lawground.trace.service.AnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {
    private final AnalysisService analysis;

    public AnalysisController(AnalysisService analysis) {
        this.analysis = analysis;
    }

    @PostMapping(value = "/questions/{id}/trace-runs", consumes = "application/json")
    public ResponseEntity<TraceDtos.Accepted> create(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TraceDtos.Input input) {
        return ResponseEntity.accepted()
                .body(
                        analysis.create(
                                ApiParameters.uuid(id),
                                input.questionRevision(),
                                ApiParameters.uuid(key)));
    }

    @GetMapping("/questions/{id}/trace-runs")
    public PageResponse<TraceDtos.Run> list(
            @PathVariable String id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return analysis.list(ApiParameters.uuid(id), ApiParameters.limit(limit), cursor);
    }

    @GetMapping("/jobs/{id}")
    public TraceDtos.Job job(@PathVariable String id) {
        return analysis.job(ApiParameters.uuid(id));
    }

    @GetMapping("/trace-runs/{id}")
    public TraceDtos.Run get(@PathVariable String id) {
        return analysis.get(ApiParameters.uuid(id));
    }

    @GetMapping("/trace-runs/{id}/result")
    public TraceDtos.Result result(@PathVariable String id) {
        return analysis.result(ApiParameters.uuid(id));
    }

    @GetMapping("/trace-runs/{id}/job")
    public TraceDtos.Job jobForRun(@PathVariable String id) {
        return analysis.jobForRun(ApiParameters.uuid(id));
    }

    @PostMapping("/trace-runs/{id}/cancel")
    public ResponseEntity<TraceDtos.Cancel> cancel(
            @PathVariable String id, jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        if (request.getInputStream().read() != -1) throw ApiParameters.invalid();
        var result = analysis.cancel(ApiParameters.uuid(id));
        return ResponseEntity.status(result.first() ? 202 : 200).body(result.body());
    }
}
