package com.lawground.evaluation;

import com.lawground.auth.MemberResolver;
import com.lawground.global.error.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class EvaluationAccess {
    private final EvaluationProperties settings;
    private final MemberResolver members;

    public EvaluationAccess(EvaluationProperties settings, MemberResolver members) {
        this.settings = settings;
        this.members = members;
    }

    public UUID requiredManager() {
        var id = members.requiredMemberId();
        if (!allowed(id))
            throw new ApiException(
                    HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "평가 관리 권한이 필요합니다.");
        return id;
    }

    public boolean allowed(UUID id) {
        return settings.enabled() && settings.managers().contains(id);
    }

    public String buildRevision() {
        return settings.buildRevision();
    }
}
