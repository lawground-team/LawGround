package com.lawground.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "members")
public class Member {
    @Id private UUID id;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Member() {}

    public UUID getId() {
        return id;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public String getDisplayName() {
        return displayName;
    }
}
