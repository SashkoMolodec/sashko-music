package com.sashkomusic.agents.discovery;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiscoveryAgentConfig {

    /** No memory and no tools of its own — one question in, three release names out. */
    @Bean
    public ReleaseRecommender releaseRecommender(@Qualifier("discoveryChatModel") ChatModel discoveryChatModel) {
        return AiServices.builder(ReleaseRecommender.class)
                .chatModel(discoveryChatModel)
                .build();
    }

    @Bean
    public DiscoveryAgent discoveryAgent(
            @Qualifier("discoveryChatModel") ChatModel discoveryChatModel,
            @Qualifier("discoveryMemoryProvider") ChatMemoryProvider memoryProvider,
            DiscoveryAgentTools tools) {
        return AiServices.builder(DiscoveryAgent.class)
                .chatModel(discoveryChatModel)
                .chatMemoryProvider(memoryProvider)
                .tools(tools)
                .build();
    }
}
