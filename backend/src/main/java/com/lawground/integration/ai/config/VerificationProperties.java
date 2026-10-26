package com.lawground.integration.ai.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in connection policy, not a selection of an LLM for the Python team. */
@ConfigurationProperties("lawground.analysis.verification")
public record VerificationProperties(
        boolean enabled, String modelName, String modelRevision, String promptVersion) {
    public VerificationProperties {
        if (enabled) new Pin(modelName, modelRevision, promptVersion);
    }

    public Pin pin() {
        return enabled ? new Pin(modelName, modelRevision, promptVersion) : null;
    }

    public record Pin(String modelName, String modelRevision, String promptVersion) {
        public Pin {
            if (!text(modelName, 200)
                    || !modelName.matches("verify:[A-Za-z0-9][A-Za-z0-9._/:-]*")
                    || !text(modelRevision, 160)
                    || !text(promptVersion, 160)
                    || synthetic(modelName.substring("verify:".length()))
                    || synthetic(modelRevision)
                    || synthetic(promptVersion))
                throw new IllegalArgumentException(
                        "Explicit non-Mock verifier name/model/prompt pins required");
        }

        private static boolean text(String s, int max) {
            return s != null
                    && !s.isBlank()
                    && s.codePointCount(0, s.length()) <= max
                    && s.indexOf('\0') < 0
                    && s.equals(s.strip())
                    && !s.contains("\r")
                    && !s.contains("\n");
        }

        private static boolean synthetic(String s) {
            return s.toLowerCase(Locale.ROOT).matches("^(mock|fixture|off)([:/._-].*|$)");
        }
    }
}
