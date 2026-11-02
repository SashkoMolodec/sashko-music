package com.sashkomusic.mainagent.bot;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Telegram updates off the long-polling thread: one task at a time per (lane, conversation), any
 * number of conversations and both lanes side by side, bounded by a fixed pool.
 * <p>
 * Before this, every update of every chat went through the polling library's single thread, so one
 * 30-second MainAgent turn held every button press in every chat. Messages of one conversation still
 * run strictly in order — the agent's memory depends on it — but buttons get their own lane and no
 * longer wait behind a turn.
 * <p>
 * Ordering is a chain of futures per key: each task starts after the previous one of its key has
 * finished, however that ended. The chain is dropped from the map once its last task is done, so idle
 * conversations cost nothing.
 */
@Slf4j
@Component
public class ConversationLanes {

    public enum Lane { MESSAGES, CALLBACKS }

    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    private final ExecutorService pool;
    private final Map<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public ConversationLanes(@Value("${telegram.updates.threads:8}") int threads) {
        this.pool = Executors.newFixedThreadPool(threads, namedThreads());
    }

    /** Queues {@code task} behind whatever is still running or waiting for this lane of this conversation. */
    public void submit(Lane lane, String conversationId, Runnable task) {
        String key = lane + ":" + conversationId;
        CompletableFuture<Void> tail = tails.compute(key, (k, current) -> {
            CompletableFuture<Void> previous = current == null ? CompletableFuture.completedFuture(null) : current;
            if (pool.isShutdown()) {
                log.warn("Dropping update for [{}] — shutting down", k);
                return current;
            }
            return previous.handle((ignored, error) -> null)
                    .thenRunAsync(() -> runLogged(k, task), pool);
        });
        // Registered only once the new tail is in the map: a task that has already finished runs this
        // callback right here, and inside compute it would find the old tail and leave the key behind.
        if (tail != null) {
            tail.whenComplete((ignored, error) -> tails.remove(key, tail));
        }
    }

    /** For work that must not wait for any lane, e.g. answering a callback query. */
    public void submitUnordered(Runnable task) {
        try {
            pool.execute(() -> runLogged("unordered", task));
        } catch (RejectedExecutionException e) {
            log.warn("Dropping unordered task — shutting down");
        }
    }

    /** Conversations with work still queued or running — for tests and diagnostics. */
    int activeKeys() {
        return tails.size();
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("Telegram updates still running after {}s — interrupting", SHUTDOWN_GRACE.toSeconds());
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void runLogged(String key, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            // The chain must survive a failed task, or one bad update would silence the conversation.
            log.error("Update task for [{}] failed", key, t);
        }
    }

    private static ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "tg-update-" + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
