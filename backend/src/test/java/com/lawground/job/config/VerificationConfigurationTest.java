package com.lawground.job.config;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class VerificationConfigurationTest {
    @Test
    void verificationRequiresExclusiveLiveMode() throws Exception {
        var config = new VerificationConfiguration();
        var off = new LiveAnalysisProperties(false, Set.of(), null, null);
        var live = new LiveAnalysisProperties(true, Set.of(UUID.randomUUID()), null, null);
        assertThatThrownBy(() -> config.verificationModeGuard(off, false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.verificationModeGuard(live, true).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        config.verificationModeGuard(live, false).afterPropertiesSet();
    }
}
