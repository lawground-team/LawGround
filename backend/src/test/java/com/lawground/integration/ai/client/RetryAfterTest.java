package com.lawground.integration.ai.client;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class RetryAfterTest {
    @Test
    void parsesSecondsAndHttpDatesWithoutTrustingInvalidHeaders() {
        var now = Instant.now();
        assertThat(AiInternalClient.retryAfter("30"))
                .isBetween(now.plusSeconds(30), now.plusSeconds(32));
        var date = now.plusSeconds(90).atZone(ZoneOffset.UTC).withNano(0);
        assertThat(AiInternalClient.retryAfter(DateTimeFormatter.RFC_1123_DATE_TIME.format(date)))
                .isEqualTo(date.toInstant());
        for (String invalid :
                new String[] {
                    null,
                    "",
                    "-1",
                    "1.2",
                    "99999999999999999999",
                    "garbage",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            now.minusSeconds(20).atZone(ZoneOffset.UTC))
                }) assertThat(AiInternalClient.retryAfter(invalid)).isNull();
    }
}
