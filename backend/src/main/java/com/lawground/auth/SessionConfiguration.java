package com.lawground.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.member.repository.MemberRepository;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {
    @Bean
    DefaultCookieSerializer cookieSerializer(Environment environment) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("SESSION");
        cookie.setCookiePath("/");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        // HTTP is only allowed in isolated local/test profiles.
        cookie.setUseSecureCookie(!environment.matchesProfiles("(local | test) & !prod"));
        return cookie;
    }

    @Bean
    AuthenticationSessionFilter authenticationSessionFilter(
            MemberRepository members, ObjectMapper mapper, Clock clock) {
        return new AuthenticationSessionFilter(members, mapper, clock);
    }

    @Bean
    FilterRegistrationBean<AuthenticationSessionFilter> sessionFilterRegistration(
            AuthenticationSessionFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Runs once inside Spring Security, after context loading.
        return registration;
    }
}
