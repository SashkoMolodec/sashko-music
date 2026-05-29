package com.sashkomusic.agents.discovery;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiscoveryAgentConfig {

    @Bean
    public DiscoveryAgent discoveryAgent(
            @Qualifier("haikuChatModel") ChatModel haikuChatModel,
            @Qualifier("discoveryMemoryProvider") ChatMemoryProvider memoryProvider,
            DiscoveryAgentTools tools) {
        return AiServices.builder(DiscoveryAgent.class)
                .chatModel(haikuChatModel)
                .chatMemoryProvider(memoryProvider)
                .tools(tools)
                .build();
    }
}
