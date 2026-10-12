package com.lawground.global.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Signed, process-lifetime keyset cursor. Restarting the server invalidates old cursors. */
@Component
public class PageCursor {
    private final byte[] key = new byte[32];
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public PageCursor() {
        new SecureRandom().nextBytes(key);
    }

    public record Position(Instant createdAt, UUID id) {}

    public String encode(String binding, Instant createdAt, UUID id) {
        byte[] payload = (binding + "|" + createdAt + "|" + id).getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    public Position decode(String cursor, String binding) {
        if (cursor == null) return null;
        if (cursor.length() > 1000) throw ApiParameters.invalid();
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) throw ApiParameters.invalid();
            byte[] payload = DECODER.decode(parts[0]);
            if (!MessageDigest.isEqual(sign(payload), DECODER.decode(parts[1])))
                throw ApiParameters.invalid();
            String[] fields = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 3 || !fields[0].equals(binding)) throw ApiParameters.invalid();
            return new Position(Instant.parse(fields[1]), ApiParameters.uuid(fields[2]));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw ApiParameters.invalid();
        }
    }

    private byte[] sign(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
