package com.sashkomusic.agents.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Bean
    public ChatMemoryStore postgresChatMemoryStore(JdbcTemplate jdbcTemplate) {
        return new PostgresChatMemoryStore(jdbcTemplate);
    }

    @Bean("mainMemoryProvider")
    public ChatMemoryProvider mainMemoryProvider(ChatMemoryStore store) {
        return convId -> MessageWindowChatMemory.builder()
                .maxMessages(32)
                .id(convId)
                .chatMemoryStore(store)
                .build();
    }

    @Bean("discoveryMemoryProvider")
    public ChatMemoryProvider discoveryMemoryProvider(ChatMemoryStore store) {
        return convId -> MessageWindowChatMemory.builder()
                .maxMessages(16)
                .id(convId)
                .chatMemoryStore(store)
                .build();
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
                .cacheSystemMessages(true)
                .cacheTools(true)
                .build();
    }
}
