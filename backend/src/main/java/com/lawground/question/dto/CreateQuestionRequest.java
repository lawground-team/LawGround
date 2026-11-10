package com.lawground.question.dto;

import com.lawground.global.validation.CodePointText;
import com.lawground.global.validation.TextPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record CreateQuestionRequest(
        @NotNull Subject subject,
        @CodePointText(max = 10000) String stem,
        @NotNull QuestionType questionType,
        @NotNull @Size(min = 5, max = 5) List<@NotNull @Valid Choice> choices,
        @NotNull @Min(1) @Max(5) Integer providedAnswerNumber,
        @NotNull LocalDate referenceDate) {
    public CreateQuestionRequest {
        stem = TextPolicy.normalize(stem);
    }

    public enum Subject {
        BROKER_LAW,
        PUBLIC_LAW
    }

    public enum QuestionType {
        SELECT_CORRECT,
        SELECT_INCORRECT
    }

    public record Choice(
            @NotNull @Min(1) @Max(5) Integer number, @CodePointText(max = 2000) String text) {
        public Choice {
            text = TextPolicy.normalize(text);
        }
    }
}
