package com.lawground.global.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.global.error.ErrorCode;
import com.lawground.global.error.ErrorResponseDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiInputFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 128 * 1024;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ApiInputFilter(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (List.of("X-Member-Id", "Member-Id", "X-Owner-Id").stream()
                .anyMatch(name -> request.getHeader(name) != null)) {
            error(request, response, 400, ErrorCode.VALIDATION_FAILED, "인증 주체는 서버에서 결정합니다.");
            return;
        }
        if (HttpMethod.POST.matches(request.getMethod())
                && request.getRequestURI().equals(request.getContextPath() + "/api/v1/photo-ocr")
                && MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(request.getContentType())) {
            // Spring Security checks authentication and CSRF before the OCR controller reads its
            // bounded PNG.
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            error(request, response, 413, ErrorCode.PAYLOAD_TOO_LARGE, "요청 본문 크기를 줄여 주세요.");
            return;
        }
        if (!List.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        // Covers chunked/unknown Content-Length without buffering an unbounded body.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            error(request, response, 413, ErrorCode.PAYLOAD_TOO_LARGE, "요청 본문 크기를 줄여 주세요.");
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private void error(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            ErrorCode code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(
                response.getWriter(),
                new ErrorResponseDTO(
                        code,
                        message,
                        (String) request.getAttribute(RequestIdFilter.ATTRIBUTE),
                        Instant.now(clock),
                        List.of()));
    }

    private static class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return bytes.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return bytes.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return bytes.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous JSON API input");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
