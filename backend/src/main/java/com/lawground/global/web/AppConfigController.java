package com.lawground.global.web;

import com.lawground.auth.OidcLoginSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppConfigController {
    private final ObjectProvider<OidcLoginSupport> oidc;
    private final Environment environment;

    public AppConfigController(ObjectProvider<OidcLoginSupport> oidc, Environment environment) {
        this.oidc = oidc;
        this.environment = environment;
    }

    @GetMapping("/api/v1/app/config")
    public ResponseEntity<Config> config() {
        // The implemented worker is Mock-only. The user UI never offers fake AI analysis.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new Config(
                                oidc.getIfAvailable() != null,
                                environment.matchesProfiles("local & !prod & !test"),
                                false));
    }

    public record Config(
            boolean googleLoginEnabled, boolean localEnvironment, boolean analysisAvailable) {}
}
