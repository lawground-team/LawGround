package com.lawground.law.dto;

import java.time.LocalDate;
import java.util.UUID;

public record LawArticleResponse(
        UUID id,
        UUID lawVersionId,
        String lawTitle,
        String articleLabel,
        String sourceText,
        String sourceUrl,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
