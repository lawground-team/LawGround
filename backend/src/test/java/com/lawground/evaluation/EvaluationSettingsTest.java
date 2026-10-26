package com.lawground.evaluation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lawground.global.error.ApiException;
import com.lawground.job.service.LiveRunPlan;
import java.util.*;
import org.junit.jupiter.api.Test;

class EvaluationSettingsTest {
    @Test
    void managersAndBoundedSizeAreExplicit() {
        assertThat(new EvaluationProperties(false, null, null, null).maxCasesPerRun())
                .isEqualTo(20);
        assertThatThrownBy(() -> new EvaluationProperties(true, Set.of(), 20, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new EvaluationProperties(true, Set.of(UUID.randomUUID()), 101, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evaluationRequiresCodeRevisionBeforeSourceInspection() {
        var access = mock(EvaluationAccess.class);
        var golden = mock(GoldenSetService.class);
        var plan = mock(LiveRunPlan.class);
        var store = mock(EvaluationStore.class);
        when(access.requiredManager()).thenReturn(UUID.randomUUID());
        when(plan.enabled()).thenReturn(true);
        when(plan.verificationEnabled()).thenReturn(true);
        var service =
                new EvaluationService(
                        access,
                        new EvaluationProperties(true, Set.of(UUID.randomUUID()), 20, ""),
                        golden,
                        plan,
                        store);
        assertThatThrownBy(() -> service.start(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(golden);
        verify(plan, never()).admit(any());
    }
}
