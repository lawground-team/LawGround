package com.lawground.auth;

import com.lawground.member.repository.MemberRepository;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final MemberResolver resolver;
    private final MemberRepository members;

    public AuthController(MemberResolver resolver, MemberRepository members) {
        this.resolver = resolver;
        this.members = members;
    }

    @GetMapping("/me")
    @Operation(operationId = "getCurrentMember", summary = "현재 로그인한 회원")
    public ResponseEntity<MemberDTO> me() {
        var member = members.findById(resolver.requiredMemberId()).orElseThrow();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new MemberDTO(member.getId(), member.getDisplayName()));
    }

    @GetMapping("/auth/csrf")
    @Operation(operationId = "getCsrfToken", summary = "현재 세션의 CSRF 토큰")
    public ResponseEntity<CsrfDTO> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new CsrfDTO(
                                token.getToken(), token.getHeaderName(), token.getParameterName()));
    }

    public record MemberDTO(UUID id, String displayName) {}

    public record CsrfDTO(String token, String headerName, String parameterName) {}
}
