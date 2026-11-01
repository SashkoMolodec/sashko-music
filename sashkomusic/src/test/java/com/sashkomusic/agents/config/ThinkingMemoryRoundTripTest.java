package com.sashkomusic.agents.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MainAgent's memory lives in Postgres as serialized JSON, and Sonnet 5.5 wants its thinking blocks
 * back unchanged in the tool loop. If the serializer dropped the thinking text or its signature, the
 * blocks would vanish between the tool call and the tool result.
 */
class ThinkingMemoryRoundTripTest {

    @Test
    void thinking_and_its_signature_survive_the_memory_store() {
        AiMessage original = AiMessage.builder()
                .thinking("")
                .attributes(Map.of("thinking_signature", "sig-abc"))
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("toolu_1").name("discoverMusic").arguments("{\"query\":\"Burial\"}").build()))
                .build();

        List<ChatMessage> restored = ChatMessageDeserializer.messagesFromJson(
                ChatMessageSerializer.messagesToJson(List.of(original)));

        AiMessage back = (AiMessage) restored.getFirst();
        assertThat(back.thinking()).isEqualTo(original.thinking());
        assertThat(back.attributes()).containsEntry("thinking_signature", "sig-abc");
        assertThat(back.toolExecutionRequests()).isEqualTo(original.toolExecutionRequests());
    }
}
