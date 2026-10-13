package com.lawground.auth;

import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/** Session identity deliberately excludes provider tokens, claims and email. */
public record MemberPrincipal(UUID memberId, Instant authenticatedAt)
        implements Principal, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return memberId.toString();
    }
}
