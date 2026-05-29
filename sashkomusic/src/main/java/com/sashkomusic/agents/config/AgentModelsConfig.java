package com.sashkomusic.agents.config;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.time.Duration;

@Configuration
public class AgentModelsConfig {

    @Bean("sonnetChatModel")
    public ChatModel sonnetChatModel(Environment env) {
        return build(env, "agents.main.model-name", "claude-sonnet-4-6",
                "agents.main.max-tokens", "2048");
    }

    @Bean("haikuChatModel")
    @Primary
    public ChatModel haikuChatModel(Environment env) {
        return build(env, "agents.discovery.model-name", "claude-haiku-4-5-20251001",
                "agents.discovery.max-tokens", "1024");
    }

    @Bean("mainMemoryProvider")
    public ChatMemoryProvider mainMemoryProvider() {
        return chatId -> MessageWindowChatMemory.withMaxMessages(16);
    }

    @Bean("discoveryMemoryProvider")
    public ChatMemoryProvider discoveryMemoryProvider() {
        return chatId -> MessageWindowChatMemory.withMaxMessages(8);
    }

    private ChatModel build(Environment env, String modelKey, String modelDefault,
                            String tokensKey, String tokensDefault) {
        String apiKey = env.getProperty("langchain4j.anthropic.chat-model.api-key", "");
        String baseUrl = env.getProperty("langchain4j.anthropic.chat-model.base-url",
                "https://api.anthropic.com/v1/");
        String modelName = env.getProperty(modelKey, modelDefault);
        int maxTokens = Integer.parseInt(env.getProperty(tokensKey, tokensDefault));
        String agentName = modelKey.startsWith("agents.main") ? "main" : "discovery";
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(3)
                .listeners(java.util.List.of(new AgentTraceListener(agentName)))
                .build();
    }
}
