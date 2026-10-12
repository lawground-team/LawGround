package com.lawground.global.error;

import com.lawground.global.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponseDTO> api(
            ApiException exception, HttpServletRequest request) {
        return response(
                exception.status(),
                exception.code(),
                exception.getMessage(),
                exception.fields(),
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> validation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        var fields =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                field ->
                                        new ErrorResponseDTO.FieldErrorDTO(
                                                field.getField(), field.getDefaultMessage()))
                        .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "입력값을 확인해 주세요.",
                fields,
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDTO> malformed(HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                ErrorCode.MALFORMED_REQUEST,
                "요청 본문 형식을 확인해 주세요.",
                List.of(),
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> unexpected(
            Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse frameworkError) {
            return response(
                    frameworkError.getStatusCode(),
                    ErrorCode.REQUEST_FAILED,
                    "요청 경로와 형식을 확인해 주세요.",
                    List.of(),
                    request);
        }
        // Avoid recording request bodies, credentials or exception messages in the general log.
        log.error("Unhandled exception type={}", exception.getClass().getName());
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR,
                "요청을 처리하지 못했습니다.",
                List.of(),
                request);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseDTO> integrity(
            org.springframework.dao.DataIntegrityViolationException exception,
            HttpServletRequest request) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains("questions_active_owner_hash_uq"))
                return response(
                        HttpStatus.CONFLICT,
                        ErrorCode.DUPLICATE_QUESTION,
                        "이미 등록한 문제입니다.",
                        List.of(),
                        request);
        }
        return unexpected(exception, request);
    }

    private ResponseEntity<ErrorResponseDTO> response(
            HttpStatusCode status,
            ErrorCode code,
            String message,
            List<ErrorResponseDTO.FieldErrorDTO> fields,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponseDTO(
                                code,
                                message,
                                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE),
                                Instant.now(clock),
                                fields));
    }
}
