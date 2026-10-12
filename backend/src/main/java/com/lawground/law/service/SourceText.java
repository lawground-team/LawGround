package com.lawground.law.service;

public final class SourceText {
    private SourceText() {}

    public static String slice(String text, int start, int end) {
        if (text == null
                || start < 0
                || end <= start
                || end > text.codePointCount(0, text.length()))
            throw new IllegalArgumentException("Invalid code-point range");
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    public static void requireQuote(String text, int start, int end, String quote) {
        if (quote == null || quote.isBlank() || !slice(text, start, end).equals(quote))
            throw new IllegalArgumentException("Quote does not match immutable source");
    }
}
