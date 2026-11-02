package com.sashkomusic.mainagent.bot;

import com.sashkomusic.mainagent.bot.state.ChatStateStore;
import com.sashkomusic.mainagent.search.FileIdCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The polling thread only routes; a long agent turn must not hold a button press in the same chat. */
class TelegramChatBotRoutingTest {

    private static final long CHAT = 42L;

    private final UserInteractionOrchestrator orchestrator = mock(UserInteractionOrchestrator.class);
    private final TelegramClient client = mock(TelegramClient.class);
    private final ConversationLanes lanes = new ConversationLanes(4);
    private final TelegramChatBot bot = new TelegramChatBot("token", "", 0L, orchestrator,
            mock(FileIdCacheService.class), mock(ChatStateStore.class), lanes, client);

    @AfterEach
    void stop() {
        lanes.shutdown();
    }

    @Test
    void consume_returns_at_once_and_a_button_is_handled_while_the_turn_still_runs() throws Exception {
        CountDownLatch turnMayFinish = new CountDownLatch(1);
        CountDownLatch buttonHandled = new CountDownLatch(1);
        when(orchestrator.handleUserRequest(any(), eq("знайди burial"))).thenAnswer(invocation -> {
            turnMayFinish.await(10, TimeUnit.SECONDS);
            return List.of();
        });
        when(orchestrator.handleCallback(any(), eq("CARD:s1:1"), any())).thenAnswer(invocation -> {
            buttonHandled.countDown();
            return List.of();
        });

        long started = System.nanoTime();
        bot.consume(List.of(text(1, "знайди burial"), click(2, "CARD:s1:1")));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);

        assertThat(buttonHandled.await(5, TimeUnit.SECONDS)).isTrue();
        verify(client, timeout(5_000)).execute(any(AnswerCallbackQuery.class));
        turnMayFinish.countDown();
        verify(orchestrator, timeout(5_000)).handleUserRequest(any(), anyString());
    }

    @Test
    void two_messages_of_one_chat_are_answered_in_order() throws Exception {
        CountDownLatch bothDone = new CountDownLatch(2);
        StringBuilder order = new StringBuilder();
        when(orchestrator.handleUserRequest(any(), anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(1);
            if (text.equals("first")) Thread.sleep(100);
            synchronized (order) { order.append(text).append(' '); }
            bothDone.countDown();
            return List.of();
        });

        bot.consume(List.of(text(1, "first"), text(2, "second")));

        assertThat(bothDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order.toString()).isEqualTo("first second ");
    }

    private static Update text(int updateId, String text) {
        Message message = message();
        message.setText(text);
        Update update = new Update();
        update.setUpdateId(updateId);
        update.setMessage(message);
        return update;
    }

    private static Update click(int updateId, String data) {
        CallbackQuery callback = new CallbackQuery();
        callback.setId("cb-" + updateId);
        callback.setData(data);
        callback.setMessage(message());
        Update update = new Update();
        update.setUpdateId(updateId);
        update.setCallbackQuery(callback);
        return update;
    }

    private static Message message() {
        Message message = new Message();
        message.setMessageId(100);
        message.setChat(Chat.builder().id(CHAT).type("private").build());
        return message;
    }
}
