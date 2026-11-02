package com.sashkomusic.agents.main;

import com.sashkomusic.agents.discovery.DiscoveryTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MainAgentConfig {

    @Bean
    public MainAgent mainAgent(
            @Qualifier("sonnetChatModel") ChatModel sonnetChatModel,
            @Qualifier("mainMemoryProvider") ChatMemoryProvider memoryProvider,
            MainAgentTools tools,
            DiscoveryTools discoveryTools) {
        return AiServices.builder(MainAgent.class)
                .chatModel(sonnetChatModel)
                .chatMemoryProvider(memoryProvider)
                .tools(tools, discoveryTools)
                .build();
    }
}
