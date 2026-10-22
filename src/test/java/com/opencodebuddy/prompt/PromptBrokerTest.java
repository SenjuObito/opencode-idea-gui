package com.opencodebuddy.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The prompt broker owns the pending prompts and the tab they belong to.
 *
 * <p>Closing a tab must reject exactly its own prompts: the daemon stops
 * waiting for an answer, and the prompts of every other tab keep waiting for
 * theirs.</p>
 */
public class PromptBrokerTest {

    @Test
    public void aTabOwnsThePromptsItRegistered() {
        PromptBroker broker = new PromptBroker();
        broker.permissions.put("perm-a", new CompletableFuture<>());
        broker.permissionSessions.put("perm-a", "ses_a");
        broker.rememberPermissionTab("perm-a", "tab-a");

        broker.askFutures.put("q-a", new CompletableFuture<>());
        broker.askSessions.put("q-a", "ses_a");
        broker.questions.put("q-a", new PromptBroker.PendingQuestion("ses_a", new JsonArray(), "tool", "/repo"));
        broker.rememberQuestionTab("q-a", "tab-a");

        assertEquals("tab-a", broker.tabIdOf("perm-a"));
        assertEquals("tab-a", broker.tabIdOf("q-a"));
        assertEquals("ses_a", broker.sessionIdOf("perm-a"));

        List<PendingPrompt> pending = broker.pendingForTab("tab-a");
        assertEquals(2, pending.size());
        assertTrue(pending.stream().anyMatch(p -> p.getKind() == PromptKind.PERMISSION));
        assertTrue(pending.stream().anyMatch(p -> p.getKind() == PromptKind.QUESTION));
    }

    @Test
    public void rejectingATabLeavesTheOtherTabsPromptsAlone() {
        PromptBroker broker = new PromptBroker();
        CompletableFuture<Integer> futureA = new CompletableFuture<>();
        CompletableFuture<Integer> futureB = new CompletableFuture<>();
        broker.permissions.put("perm-a", futureA);
        broker.permissionSessions.put("perm-a", "ses_a");
        broker.rememberPermissionTab("perm-a", "tab-a");
        broker.permissions.put("perm-b", futureB);
        broker.permissionSessions.put("perm-b", "ses_b");
        broker.rememberPermissionTab("perm-b", "tab-b");

        List<String> rejected = broker.rejectForTab("tab-a");

        assertEquals(List.of("perm-a"), rejected);
        assertTrue("the closed tab's prompt must not stay pending", futureA.isCompletedExceptionally());
        assertFalse("another tab's prompt must not be touched", futureB.isDone());
        assertEquals(1, broker.permissions.size());
        assertTrue(broker.permissions.containsKey("perm-b"));
    }

    @Test
    public void rejectingATabAlsoRejectsItsQuestionsAndPlans() {
        PromptBroker broker = new PromptBroker();
        CompletableFuture<JsonObject> ask = new CompletableFuture<>();
        CompletableFuture<JsonObject> plan = new CompletableFuture<>();
        broker.askFutures.put("q-a", ask);
        broker.askSessions.put("q-a", "ses_a");
        broker.rememberQuestionTab("q-a", "tab-a");
        broker.planFutures.put("plan-a", plan);
        broker.planSessions.put("plan-a", "ses_a");
        broker.rememberPlanTab("plan-a", "tab-a");

        broker.rejectForTab("tab-a");

        assertTrue(ask.isCompletedExceptionally());
        assertTrue(plan.isCompletedExceptionally());
        assertEquals(0, broker.pendingCount());
    }

    @Test
    public void aPromptWithoutATabIsNotRejectedByAnyTab() {
        PromptBroker broker = new PromptBroker();
        CompletableFuture<Integer> future = new CompletableFuture<>();
        broker.permissions.put("perm-x", future);
        broker.permissionSessions.put("perm-x", "ses_x");

        assertTrue(broker.rejectForTab("tab-a").isEmpty());
        assertFalse(future.isDone());

        try {
            broker.rejectForTab(null);
        } catch (RuntimeException e) {
            fail("rejecting a null tab must not throw: " + e.getMessage());
        }
    }
}
