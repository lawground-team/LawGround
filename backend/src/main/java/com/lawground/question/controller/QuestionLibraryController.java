package com.lawground.question.controller;

import com.lawground.global.web.ApiParameters;
import com.lawground.global.web.PageResponse;
import com.lawground.question.dto.CreateQuestionRequest;
import com.lawground.question.dto.QuestionListItem;
import com.lawground.question.dto.QuestionResponse;
import com.lawground.question.service.QuestionLibraryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/questions")
public class QuestionLibraryController {
    private final QuestionLibraryService library;

    public QuestionLibraryController(QuestionLibraryService library) {
        this.library = library;
    }

    @GetMapping
    public PageResponse<QuestionListItem> list(
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return library.list(scope, ApiParameters.limit(limit), cursor);
    }

    @PutMapping(value = "/{id}", consumes = "application/json")
    public ResponseEntity<QuestionResponse> update(
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String match,
            @Valid @RequestBody CreateQuestionRequest input) {
        var result = library.update(ApiParameters.uuid(id), ApiParameters.revision(match), input);
        return ResponseEntity.ok().eTag(Long.toString(result.revision())).body(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String match) {
        library.delete(ApiParameters.uuid(id), ApiParameters.revision(match));
        return ResponseEntity.noContent().build();
    }
}
