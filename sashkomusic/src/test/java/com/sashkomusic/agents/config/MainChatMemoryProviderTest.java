package com.sashkomusic.agents.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LangChain4j asks the provider for a conversation's memory once and keeps that object for the life of
 * the JVM, so compaction has to work through an explicit call — and show up in the object it already holds.
 */
class MainChatMemoryProviderTest {

    private static final String CONVERSATION = "42";

    private final AtomicInteger summaries = new AtomicInteger();
    private final ChatModel haiku = new ChatModel() {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            summaries.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from("user looked for burial")).build();
        }
    };
    private final MainChatMemoryProvider sut = new MainChatMemoryProvider(new InMemoryChatMemoryStore(), haiku);

    @Test
    void a_short_conversation_is_left_alone() {
        ChatMemory memory = sut.get(CONVERSATION);
        addTurns(memory, 12);

        sut.compact(CONVERSATION);

        assertThat(memory.messages()).hasSize(24);
        assertThat(summaries).hasValue(0);
    }

    @Test
    void the_memory_the_agent_already_holds_is_compacted_at_the_turn_boundary() {
        ChatMemory heldByAgent = sut.get(CONVERSATION);
        addTurns(heldByAgent, 13);

        sut.compact(CONVERSATION);

        List<ChatMessage> messages = heldByAgent.messages();
        assertThat(summaries).hasValue(1);
        assertThat(messages).hasSize(12);
        assertThat(((UserMessage) messages.get(0)).singleText()).isEqualTo("Підсумок попередньої розмови:");
        assertThat(((AiMessage) messages.get(1)).text()).isEqualTo("user looked for burial");
        assertThat(messages.get(2)).isInstanceOf(UserMessage.class);
        assertThat(((AiMessage) messages.getLast()).text()).isEqualTo("answer 12");
    }

    @Test
    void writing_now_playing_into_a_full_memory_compacts_it() {
        addTurns(sut.get(CONVERSATION), 12);

        sut.appendUserAndAi(CONVERSATION, "/np", "зараз грає: burial — archangel");

        assertThat(summaries).hasValue(1);
        assertThat(((AiMessage) sut.get(CONVERSATION).messages().getLast()).text())
                .isEqualTo("зараз грає: burial — archangel");
    }

    private static void addTurns(ChatMemory memory, int turns) {
        for (int i = 0; i < turns; i++) {
            memory.add(UserMessage.from("question " + i));
            memory.add(AiMessage.from("answer " + i));
        }
    }
}
