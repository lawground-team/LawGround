package com.lawground.job.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lawground.law.search.FixtureIndexWriter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class MockModeTest {
    @Test
    void mockAnalysisIsExplicitLocalOrTestAndAlwaysRejectedInProd() throws Exception {
        var configuration = new MockQueueConfiguration();
        for (String profile : List.of("local", "test")) {
            var env = new MockEnvironment();
            env.setActiveProfiles(profile);
            configuration.mockGuard(env).afterPropertiesSet();
        }
        for (String[] profiles :
                new String[][] {{}, {"prod"}, {"local", "prod"}, {"test", "prod"}, {"staging"}}) {
            var env = new MockEnvironment();
            env.setActiveProfiles(profiles);
            assertThatThrownBy(() -> configuration.mockGuard(env).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void handVectorsCannotBeWrittenOutsideIsolatedTestEvenWithFixtureRevision() {
        for (String[] profiles : new String[][] {{"local"}, {"prod"}, {"test", "prod"}}) {
            var env = new MockEnvironment();
            env.setActiveProfiles(profiles);
            var jdbc = mock(JdbcTemplate.class);
            var writer = new FixtureIndexWriter(jdbc, env);
            assertThatThrownBy(
                            () ->
                                    writer.write(
                                            UUID.randomUUID(),
                                            "fixture-v1",
                                            "fixture-t1",
                                            new float[1024],
                                            List.of()))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(jdbc);
        }
    }
}
