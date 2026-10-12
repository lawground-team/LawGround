package com.lawground.global.web;

import com.lawground.global.error.ApiException;
import com.lawground.global.error.ErrorCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class ApiParameters {
    private ApiParameters() {}

    public static ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "입력 형식을 확인해 주세요.");
    }

    public static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw invalid();
            return id;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid();
        }
    }

    public static int limit(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]?")) throw invalid();
        int n = Integer.parseInt(value);
        if (n > 50) throw invalid();
        return n;
    }

    public static long revision(String value) {
        if (value == null)
            throw new ApiException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    ErrorCode.PRECONDITION_REQUIRED,
                    "If-Match가 필요합니다.");
        if (!value.matches("\"[1-9][0-9]{0,18}\"")) throw invalid();
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }
}
