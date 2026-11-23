package com.lawground.global.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lawground.auth.OidcLoginSupport;
import com.lawground.job.service.LiveRunPlan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppConfigController {
    private final ObjectProvider<OidcLoginSupport> oidc;
    private final Environment environment;
    private final LiveRunPlan live;
    private final boolean uiEnabled;
    private final boolean serverOcr;

    public AppConfigController(
            ObjectProvider<OidcLoginSupport> oidc,
            Environment environment,
            LiveRunPlan live,
            @Value("${lawground.ui.analysis-enabled:false}") boolean uiEnabled,
            @Value("${lawground.ocr.server-enabled:false}") boolean serverOcr) {
        this.oidc = oidc;
        this.environment = environment;
        this.live = live;
        this.uiEnabled = uiEnabled;
        this.serverOcr = serverOcr;
    }

    @GetMapping("/api/v1/app/config")
    public ResponseEntity<Config> config() {
        // Explicit operator opt-in; inference readiness is still checked by the actual pipeline.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new Config(
                                oidc.getIfAvailable() != null,
                                environment.matchesProfiles("local & !prod & !test"),
                                uiEnabled && live.enabled() && live.verificationEnabled(),
                                serverOcr && environment.matchesProfiles("local & !prod & !test")));
    }

    public record Config(
            boolean googleLoginEnabled,
            boolean localEnvironment,
            boolean analysisAvailable,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean serverOcr) {}
}
