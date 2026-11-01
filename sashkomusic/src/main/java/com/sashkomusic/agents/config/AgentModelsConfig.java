package com.sashkomusic.agents.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicServerTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;

@Configuration
public class AgentModelsConfig {

    /**
     * Sonnet 5.5 always thinks (adaptive; {@code disabled} is a 400), so the only knob here is
     * thinking-block handling: they are kept on the AiMessage and sent back unchanged, because the
     * tool loop needs them.
     * <p>
     * Do not pass {@code customParameters} (effort, fallbacks): langchain4j 1.10.0 writes them twice,
     * flattened and again under {@code custom_parameters}, and Anthropic rejects every request with
     * a 400. {@code SonnetRequestBodyTest} checks the real request body.
     */
    @Bean("sonnetChatModel")
    public ChatModel sonnetChatModel(Environment env) {
        return builder(env, "agents.main.model-name", "claude-sonnet-5-5",
                "agents.main.max-tokens", "8192", "main")
                .returnThinking(true)
                .sendThinking(true)
                .build();
    }

    @Bean("haikuChatModel")
    @Primary
    public ChatModel haikuChatModel(Environment env) {
        return build(env, "agents.discovery.model-name", "claude-haiku-4-5-20251001",
                "agents.discovery.max-tokens", "1024");
    }

    /**
     * Dedicated model bean for DiscoveryAgent (NOT the shared {@code haikuChatModel} — LibraryAgent
     * and the various extractor AiServices also use that one, and giving every one of them a
     * web-search tool it never needs would grow every request's tool schema for no reason).
     * Anthropic's server-side web_search tool runs entirely on Anthropic's infrastructure — the
     * model calls it and gets results back within the same API response, no client-side execution
     * or WebSearchService/jsoup-DuckDuckGo-scraping needed. Verified live against this exact model
     * (claude-haiku-4-5-20251001) before wiring it in.
     */
    @Bean("discoveryChatModel")
    public ChatModel discoveryChatModel(Environment env) {
        AnthropicServerTool webSearch = AnthropicServerTool.builder()
                .type("web_search_20250305")
                .name("web_search")
                .addAttribute("max_uses", 3)
                .build();
        return builder(env, "agents.discovery.model-name", "claude-haiku-4-5-20251001",
                "agents.discovery.max-tokens", "1024", "discovery")
                .serverTools(List.of(webSearch))
                .build();
    }

    @Bean
    public ChatMemoryStore postgresChatMemoryStore(JdbcTemplate jdbcTemplate) {
        return new PostgresChatMemoryStore(jdbcTemplate);
    }

    @Bean("discoveryMemoryProvider")
    public ChatMemoryProvider discoveryMemoryProvider(ChatMemoryStore store) {
        return convId -> MessageWindowChatMemory.builder()
                .maxMessages(16)
                .id(convId)
                .chatMemoryStore(store)
                .build();
    }

    @Bean("libraryMemoryProvider")
    public ChatMemoryProvider libraryMemoryProvider(ChatMemoryStore store) {
        return convId -> MessageWindowChatMemory.builder()
                .maxMessages(16)
                .id(convId)
                .chatMemoryStore(store)
                .build();
    }

    private ChatModel build(Environment env, String modelKey, String modelDefault,
                            String tokensKey, String tokensDefault) {
        String agentName = modelKey.startsWith("agents.main") ? "main" : "discovery";
        return builder(env, modelKey, modelDefault, tokensKey, tokensDefault, agentName).build();
    }

    private AnthropicChatModel.AnthropicChatModelBuilder builder(Environment env, String modelKey, String modelDefault,
                                                                  String tokensKey, String tokensDefault, String agentName) {
        String apiKey = env.getProperty("langchain4j.anthropic.chat-model.api-key", "");
        String baseUrl = env.getProperty("langchain4j.anthropic.chat-model.base-url",
                "https://api.anthropic.com/v1/");
        String modelName = env.getProperty(modelKey, modelDefault);
        int maxTokens = Integer.parseInt(env.getProperty(tokensKey, tokensDefault));
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(3)
                .listeners(List.of(new AgentTraceListener(agentName)))
                .cacheSystemMessages(true)
                .cacheTools(true);
    }
}
