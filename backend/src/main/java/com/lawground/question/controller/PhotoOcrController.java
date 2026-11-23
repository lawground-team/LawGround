package com.lawground.question.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local & !prod & !test")
@ConditionalOnProperty(name = "lawground.ocr.server-enabled", havingValue = "true")
public class PhotoOcrController {
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private final Semaphore available = new Semaphore(1);
    private final RestClient worker;

    public PhotoOcrController(
            @Value("${LAWGROUND_OCR_TOKEN:}") String token,
            @Value("${server.address:}") String address) {
        if (token.isBlank() || !("127.0.0.1".equals(address) || "::1".equals(address)))
            throw new IllegalStateException(
                    "Server OCR preview requires a token and loopback binding");
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(Duration.ofSeconds(3))
                                .build());
        factory.setReadTimeout(Duration.ofSeconds(35));
        worker =
                RestClient.builder()
                        .requestFactory(factory)
                        .baseUrl("http://127.0.0.1:8002")
                        .defaultHeader("X-OCR-Token", token)
                        .build();
    }

    @PostMapping(path = "/api/v1/photo-ocr", consumes = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<OcrText> recognize(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_IMAGE_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        if (!available.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        try {
            byte[] png = request.getInputStream().readNBytes(MAX_IMAGE_BYTES + 1);
            if (png.length > MAX_IMAGE_BYTES)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            if (png.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            var result =
                    worker.post()
                            .uri("/recognize")
                            .contentType(MediaType.IMAGE_PNG)
                            .body(png)
                            .retrieve()
                            .body(OcrText.class);
            if (result == null || result.text() == null)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 422 || status == 429)
                throw new ResponseStatusException(exception.getStatusCode());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            available.release();
        }
    }

    record OcrText(String text) {}
}
