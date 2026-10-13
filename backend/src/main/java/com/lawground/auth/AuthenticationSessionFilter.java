package com.lawground.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.global.error.ErrorCode;
import com.lawground.global.error.ErrorResponseDTO;
import com.lawground.global.web.RequestIdFilter;
import com.lawground.member.repository.MemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthenticationSessionFilter extends OncePerRequestFilter {
    private final MemberRepository members;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuthenticationSessionFilter(MemberRepository members, ObjectMapper mapper, Clock clock) {
        this.members = members;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof MemberPrincipal principal) {
            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/"))
                response.setHeader("Cache-Control", "no-store");
            Instant now = Instant.now(clock);
            if (principal.authenticatedAt() == null
                    || principal.authenticatedAt().isAfter(now)
                    || !now.isBefore(principal.authenticatedAt().plus(Duration.ofHours(12)))) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                // Continue anonymously: public CSRF can establish a fresh session; APIs return 401.
            } else {
                var member = members.findById(principal.memberId());
                if (member.isEmpty() || !member.get().isActive()) {
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    mapper.writeValue(
                            response.getWriter(),
                            new ErrorResponseDTO(
                                    ErrorCode.ACCESS_DENIED,
                                    "접근할 수 없습니다.",
                                    (String) request.getAttribute(RequestIdFilter.ATTRIBUTE),
                                    now,
                                    List.of()));
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
