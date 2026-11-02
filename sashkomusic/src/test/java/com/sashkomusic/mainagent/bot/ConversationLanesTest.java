package com.sashkomusic.mainagent.bot;

import com.sashkomusic.mainagent.bot.ConversationLanes.Lane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationLanesTest {

    private final ConversationLanes lanes = new ConversationLanes(4);

    @AfterEach
    void stop() {
        lanes.shutdown();
    }

    @Test
    void one_conversations_messages_run_one_at_a_time_in_arrival_order() throws Exception {
        List<Integer> order = new CopyOnWriteArrayList<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(50);

        IntStream.range(0, 50).forEach(i -> lanes.submit(Lane.MESSAGES, "42", () -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            order.add(i);
            sleep(1);
            running.decrementAndGet();
            done.countDown();
        }));

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
        assertThat(maxRunning.get()).isEqualTo(1);
    }

    @Test
    void a_button_press_does_not_wait_for_a_running_turn_in_the_same_chat() throws Exception {
        CountDownLatch turnMayFinish = new CountDownLatch(1);
        CountDownLatch buttonHandled = new CountDownLatch(1);

        lanes.submit(Lane.MESSAGES, "42", () -> await(turnMayFinish));
        lanes.submit(Lane.CALLBACKS, "42", buttonHandled::countDown);

        assertThat(buttonHandled.await(5, TimeUnit.SECONDS)).isTrue();
        turnMayFinish.countDown();
    }

    @Test
    void a_slow_chat_does_not_hold_another_chat() throws Exception {
        CountDownLatch slowMayFinish = new CountDownLatch(1);
        CountDownLatch otherHandled = new CountDownLatch(1);

        lanes.submit(Lane.MESSAGES, "42", () -> await(slowMayFinish));
        lanes.submit(Lane.MESSAGES, "-100:7", otherHandled::countDown);

        assertThat(otherHandled.await(5, TimeUnit.SECONDS)).isTrue();
        slowMayFinish.countDown();
    }

    @Test
    void a_failed_update_does_not_silence_the_conversation() throws Exception {
        CountDownLatch next = new CountDownLatch(1);

        lanes.submit(Lane.MESSAGES, "42", () -> { throw new IllegalStateException("boom"); });
        lanes.submit(Lane.MESSAGES, "42", next::countDown);

        assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void idle_conversations_leave_nothing_behind() throws Exception {
        CountDownLatch done = new CountDownLatch(10);
        IntStream.range(0, 10).forEach(i -> lanes.submit(Lane.CALLBACKS, "chat-" + i, done::countDown));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        long deadline = System.currentTimeMillis() + 5_000;
        while (lanes.activeKeys() > 0 && System.currentTimeMillis() < deadline) sleep(5);
        assertThat(lanes.activeKeys()).isZero();
    }

    @Test
    void shutdown_lets_a_running_turn_finish() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        lanes.submit(Lane.MESSAGES, "42", () -> {
            started.countDown();
            sleep(200);
            finished.incrementAndGet();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        lanes.shutdown();

        assertThat(finished.get()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
