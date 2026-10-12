package com.lawground.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.auth.LocalDevelopmentAuth;
import com.lawground.global.error.ErrorCode;
import com.lawground.global.error.ErrorResponseDTO;
import com.lawground.global.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper mapper,
            Clock clock,
            Environment environment,
            @Value("${lawground.security.dev-auth-enabled:false}") boolean devAuthEnabled,
            @Value("${server.address:127.0.0.1}") String address,
            @Value("${lawground.security.allow-local-docs:false}") boolean allowLocalDocs)
            throws Exception {
        LocalDevelopmentAuth.validate(devAuthEnabled, environment, address);
        if (devAuthEnabled)
            http.addFilterBefore(new LocalDevelopmentAuth(), AnonymousAuthenticationFilter.class);
        http.authorizeHttpRequests(
                requests -> {
                    requests.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (allowLocalDocs) {
                        requests.requestMatchers(
                                        "/v3/api-docs",
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .permitAll();
                    }
                    requests.requestMatchers(HttpMethod.POST, "/api/v1/questions").authenticated();
                    requests.requestMatchers(HttpMethod.GET, "/api/v1/questions/*").permitAll();
                    requests.requestMatchers(
                                    HttpMethod.GET, "/api/v1/questions", "/api/v1/law-articles/*")
                            .permitAll();
                    requests.requestMatchers(HttpMethod.PUT, "/api/v1/questions/*").authenticated();
                    requests.requestMatchers(HttpMethod.DELETE, "/api/v1/questions/*")
                            .authenticated();
                    requests.requestMatchers(
                                    "/api/v1/questions/*/trace-runs",
                                    "/api/v1/jobs/*",
                                    "/api/v1/trace-runs/*",
                                    "/api/v1/trace-runs/*/result",
                                    "/api/v1/trace-runs/*/cancel")
                            .authenticated();
                    if (devAuthEnabled)
                        requests.requestMatchers(HttpMethod.GET, "/api/v1/dev/csrf")
                                .authenticated();
                    requests.anyRequest().denyAll();
                });
        http.formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        // Keep CSRF protection enabled for future session-based browser requests.
        http.exceptionHandling(
                errors ->
                        errors.authenticationEntryPoint(
                                        (request, response, exception) ->
                                                writeError(
                                                        request,
                                                        response,
                                                        mapper,
                                                        clock,
                                                        401,
                                                        ErrorCode.AUTHENTICATION_REQUIRED,
                                                        "인증이 필요합니다."))
                                .accessDeniedHandler(
                                        (request, response, exception) ->
                                                writeError(
                                                        request,
                                                        response,
                                                        mapper,
                                                        clock,
                                                        403,
                                                        ErrorCode.ACCESS_DENIED,
                                                        "접근할 수 없습니다.")));
        return http.build();
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper mapper,
            Clock clock,
            int status,
            ErrorCode code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(
                response.getWriter(),
                new ErrorResponseDTO(
                        code,
                        message,
                        (String) request.getAttribute(RequestIdFilter.ATTRIBUTE),
                        Instant.now(clock),
                        List.of()));
    }
}
