package com.lawground.batch;

import com.lawground.auth.MemberResolver;
import com.lawground.global.error.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class BatchAccess {
    private final BatchProperties settings;
    private final MemberResolver members;

    public BatchAccess(BatchProperties settings, MemberResolver members) {
        this.settings = settings;
        this.members = members;
    }

    public boolean allowed(UUID id) {
        return settings.enabled() && settings.managers().contains(id);
    }

    public String buildRevision() {
        return settings.buildRevision();
    }

    public UUID required() {
        var id = members.requiredMemberId();
        if (!allowed(id))
            throw new ApiException(
                    HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "기출 배치 관리 권한이 필요합니다.");
        return id;
    }
}
