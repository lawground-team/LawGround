package com.lawground.job.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lawground.integration.ai.client.AiClientException;
import com.lawground.job.config.LiveAnalysisProperties;
import com.lawground.job.config.LiveQueueConfiguration;
import com.lawground.law.search.LiveRetrievalService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class LiveWorkerTest {
    @Test
    void emptyQueueBodyIsRejectedBeforeClaim() throws Exception {
        var store = mock(JobStore.class);
        var channel = mock(com.rabbitmq.client.Channel.class);
        var worker =
                new LiveJobWorker(
                        store,
                        mock(LiveRunPlan.class),
                        mock(LiveRetrievalService.class),
                        new ObjectMapper(),
                        null);
        var props = new org.springframework.amqp.core.MessageProperties();
        props.setDeliveryTag(9);
        try {
            worker.receive(new org.springframework.amqp.core.Message(new byte[0], props), channel);
        } finally {
            worker.close();
        }
        verify(channel).basicReject(9, false);
        verifyNoInteractions(store);
    }

    @Test
    void timeoutRetriesAtWorkerAndDatabaseFailuresPropagate() {
        var store = mock(JobStore.class);
        var plan = mock(LiveRunPlan.class);
        var retrieval = mock(LiveRetrievalService.class);
        var json = new ObjectMapper();
        var id = UUID.randomUUID();
        var claim =
                new JobStore.Claim(
                        id,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        Instant.now().plusSeconds(600),
                        json.createObjectNode());
        var config = mock(LiveRunPlan.Config.class);
        var query = mock(LiveRetrievalService.Query.class);
        var saved = json.createObjectNode();
        when(store.claim(eq(id), anyString(), eq("LIVE"))).thenReturn(claim);
        when(store.liveWork(claim))
                .thenReturn(new JobStore.LiveWork(LiveRunPlan.PIPELINE, saved, saved));
        when(plan.read(saved)).thenReturn(config);
        when(plan.query(config, claim.snapshot())).thenReturn(query);
        when(store.beginStage(eq(claim), any())).thenReturn(JobStore.Start.STARTED);
        when(store.finishStage(claim, JobStore.Stage.PREPARE)).thenReturn(true);
        when(retrieval.search(query))
                .thenThrow(
                        new AiClientException(
                                AiClientException.Kind.TIMEOUT, 0, "DEADLINE_EXCEEDED", true));
        var worker = new LiveJobWorker(store, plan, retrieval, json, null);
        try {
            worker.process(id);
            verify(store).retry(claim, "AI_UNAVAILABLE", null);
            verify(store, never()).failLive(any(), any());
            reset(retrieval);
            when(retrieval.search(query))
                    .thenThrow(
                            new org.springframework.dao.DataAccessResourceFailureException(
                                    "synthetic"));
            assertThatThrownBy(() -> worker.process(id))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            verify(store, times(1)).retry(any(), anyString(), any());
        } finally {
            worker.close();
        }
    }

    @Test
    void liveModeRequiresExplicitCorpusBoundedBudgetAndMutualExclusion() throws Exception {
        assertThat(new LiveAnalysisProperties(false, null, null, null).candidateLimit())
                .isEqualTo(5);
        assertThatThrownBy(() -> new LiveAnalysisProperties(true, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveAnalysisProperties(true, Set.of(UUID.randomUUID()), 31, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveAnalysisProperties(true, Set.of(UUID.randomUUID()), 1, 5))
                .isInstanceOf(IllegalArgumentException.class);
        var plan = mock(LiveRunPlan.class);
        assertThatThrownBy(
                        () ->
                                new LiveQueueConfiguration()
                                        .liveAnalysisGuard(true, plan)
                                        .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(plan);
        new LiveQueueConfiguration().liveAnalysisGuard(false, plan).afterPropertiesSet();
        verify(plan).validateConfiguration();
    }

    @Test
    void malformedSnapshotCannotDropAnswerNegationOrOneOfFiveChoices() {
        var json = new ObjectMapper();
        var snapshot =
                json.createObjectNode()
                        .put("subject", "BROKER_LAW")
                        .put("questionType", "SELECT_INCORRECT")
                        .put("stem", "옳지 않은 것은?😀")
                        .put("revision", 1)
                        .put("providedAnswerNumber", 2);
        var choices = snapshot.putArray("choices");
        for (int i = 5; i >= 1; i--) choices.addObject().put("number", i).put("text", "선지 " + i);
        assertThat(LiveRunPlan.queryText(snapshot))
                .contains("옳지 않은", "😀", "providedAnswerNumber: 2")
                .containsSubsequence(
                        "choice[1]", "choice[2]", "choice[3]", "choice[4]", "choice[5]");
        choices.remove(0);
        assertThatThrownBy(() -> LiveRunPlan.queryText(snapshot)).hasMessage("SNAPSHOT_INVALID");
    }
}
