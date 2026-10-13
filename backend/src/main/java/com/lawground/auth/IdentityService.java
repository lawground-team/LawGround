package com.lawground.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public IdentityService(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Only called after the OAuth framework has validated the ID token. */
    public UUID login(String issuer, String subject, String displayName) {
        if (issuer == null
                || issuer.isBlank()
                || issuer.length() > 255
                || subject == null
                || subject.isBlank()
                || subject.length() > 255) {
            throw new IllegalArgumentException("Invalid verified identity");
        }
        String canonicalIssuer =
                "accounts.google.com".equals(issuer) ? "https://accounts.google.com" : issuer;
        try {
            return transaction.execute(
                    status -> {
                        UUID existing = existing(canonicalIssuer, subject);
                        if (existing != null) return existing;
                        UUID id = UUID.randomUUID();
                        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                        jdbc.update(
                                "INSERT INTO members(id,display_name,status,created_at,updated_at) VALUES (?,?,'ACTIVE',?,?)",
                                id,
                                safeName(displayName),
                                java.sql.Timestamp.from(now),
                                java.sql.Timestamp.from(now));
                        jdbc.update(
                                "INSERT INTO auth_identities(id,member_id,provider,issuer,subject,created_at,last_login_at) VALUES (?,?,'GOOGLE',?,?,?,?)",
                                UUID.randomUUID(),
                                id,
                                canonicalIssuer,
                                subject,
                                java.sql.Timestamp.from(now),
                                java.sql.Timestamp.from(now));
                        return id;
                    });
        } catch (DuplicateKeyException raced) {
            // The failed transaction rolled back BOTH inserts. Re-read in a fresh transaction.
            return transaction.execute(
                    status -> {
                        UUID existing = existing(canonicalIssuer, subject);
                        if (existing == null) throw raced;
                        return existing;
                    });
        }
    }

    private UUID existing(String issuer, String subject) {
        var rows =
                jdbc.query(
                        "SELECT i.member_id,m.status FROM auth_identities i JOIN members m ON m.id=i.member_id WHERE i.provider='GOOGLE' AND i.issuer=? AND i.subject=? FOR UPDATE OF m",
                        (row, n) -> {
                            if (!"ACTIVE".equals(row.getString("status")))
                                throw new DisabledException("Member unavailable");
                            return row.getObject("member_id", UUID.class);
                        },
                        issuer,
                        subject);
        if (rows.isEmpty()) return null;
        jdbc.update(
                "UPDATE auth_identities SET last_login_at=? WHERE provider='GOOGLE' AND issuer=? AND subject=?",
                java.sql.Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)),
                issuer,
                subject);
        return rows.getFirst();
    }

    static String safeName(String input) {
        if (input == null) return "LawGround 사용자";
        var value = new StringBuilder();
        input.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .limit(80)
                .forEach(value::appendCodePoint);
        return value.toString().isBlank() ? "LawGround 사용자" : value.toString().strip();
    }
}
