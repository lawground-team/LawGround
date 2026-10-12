package com.lawground.law.search;

import java.util.List;

public final class SearchValues {
    private SearchValues() {}

    public static String vector(float[] values) {
        if (values == null || values.length != 1024)
            throw new IllegalArgumentException("Expected 1024 dimensions");
        double norm = 0;
        var text = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Non-finite vector");
            norm += (double) values[i] * values[i];
            if (i > 0) text.append(',');
            text.append(values[i]);
        }
        if (norm == 0) throw new IllegalArgumentException("Cosine is undefined for a zero vector");
        return text.append(']').toString();
    }

    public static String tokens(List<String> tokens) {
        if (tokens == null || tokens.size() > 10000)
            throw new IllegalArgumentException("Invalid tokens");
        for (String token : tokens)
            if (token == null
                    || token.isBlank()
                    || token.length() > 200
                    || token.codePoints().anyMatch(Character::isWhitespace)
                    || token.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Tokens must be nonblank atomic strings");
        return String.join(" ", tokens);
    }

    public static void revision(String value) {
        if (value == null || value.isBlank() || value.length() > 160)
            throw new IllegalArgumentException("Missing exact model/tokenizer revision");
    }
}
