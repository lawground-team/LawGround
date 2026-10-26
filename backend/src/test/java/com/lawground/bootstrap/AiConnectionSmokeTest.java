package com.lawground.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lawground.integration.ai.client.AiInternalClient.Capability;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AiConnectionSmokeTest {
    @Test
    void rejectsNonlocalMixedProfilesAndExternalBinding() {
        for (String[] profiles :
                new String[][] {{"prod"}, {"test"}, {"local", "prod"}, {"local", "test"}, {}}) {
            var env = new MockEnvironment();
            env.setActiveProfiles(profiles);
            assertThatThrownBy(() -> AiConnectionSmoke.validateEnvironment(env))
                    .isInstanceOf(IllegalStateException.class);
        }
        var env = new MockEnvironment().withProperty("server.address", "0.0.0.0");
        env.setActiveProfiles("local");
        assertThatThrownBy(() -> AiConnectionSmoke.validateEnvironment(env))
                .isInstanceOf(IllegalStateException.class);
        var missingAddress = new MockEnvironment();
        missingAddress.setActiveProfiles("local");
        assertThatThrownBy(() -> AiConnectionSmoke.validateEnvironment(missingAddress))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void permitsOnlyExplicitLocalCapabilities() {
        var env = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        env.setActiveProfiles("local");
        assertThatCode(() -> AiConnectionSmoke.validateEnvironment(env)).doesNotThrowAnyException();
        assertThat(AiConnectionSmoke.parseCapabilities("TOKENIZE,EMBED"))
                .containsExactlyInAnyOrder(Capability.TOKENIZE, Capability.EMBED);
        assertThat(AiConnectionSmoke.parseCapabilities("RERANK"))
                .containsExactly(Capability.RERANK);
        for (String invalid : new String[] {"", "VERIFY", "TOKENIZE,", "EMBED,EMBED"}) {
            assertThatThrownBy(() -> AiConnectionSmoke.parseCapabilities(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
