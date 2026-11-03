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

    /** Shared by LibraryAgent, the summarizer and the extractor AiServices — labelled "haiku" in cost traces. */
    @Bean("haikuChatModel")
    @Primary
    public ChatModel haikuChatModel(Environment env) {
        return builder(env, "agents.haiku.model-name", "claude-haiku-4-5-20251001",
                "agents.haiku.max-tokens", "4096", "haiku")
                .build();
    }

    /**
     * Haiku with Anthropic's server-side web_search, for the single-shot services that need the web:
     * {@code MusicResearcher}, {@code ReleaseRecommender}, {@code ListenLinkWebSearch}. Kept apart from
     * the shared {@code haikuChatModel} — giving every extractor a web-search tool it never needs would
     * grow every request's tool schema for no reason — and off Sonnet, whose memory and tool loop carry
     * thinking blocks that server-tool blocks would have to be interleaved with.
     * Anthropic's server-side web_search tool runs entirely on Anthropic's infrastructure — the
     * model calls it and gets results back within the same API response, no client-side execution
     * or WebSearchService/jsoup-DuckDuckGo-scraping needed. Its own model property, so the extractors on
     * {@code haikuChatModel} keep theirs: Haiku 5.5 with web_search_20250305 was verified live on 2026-11-03.
     * Haiku 5.5 thinks by default and the thinking counts toward max-tokens, hence the larger ceiling.
     */
    @Bean("webSearchChatModel")
    public ChatModel webSearchChatModel(Environment env) {
        AnthropicServerTool webSearch = AnthropicServerTool.builder()
                .type("web_search_20250305")
                .name("web_search")
                .addAttribute("max_uses", 3)
                .build();
        return builder(env, "agents.web-search.model-name", "claude-haiku-5-5",
                "agents.web-search.max-tokens", "8192", "web-search")
                .serverTools(List.of(webSearch))
                .build();
    }

    @Bean
    public ChatMemoryStore postgresChatMemoryStore(JdbcTemplate jdbcTemplate) {
        return new PostgresChatMemoryStore(jdbcTemplate);
    }

    @Bean("libraryMemoryProvider")
    public ChatMemoryProvider libraryMemoryProvider(ChatMemoryStore store) {
        return convId -> MessageWindowChatMemory.builder()
                .maxMessages(16)
                .id(convId)
                .chatMemoryStore(store)
                .build();
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
