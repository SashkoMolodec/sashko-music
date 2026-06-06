package com.sashkomusic.agents.library;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LibraryAgentConfig {

    @Bean
    public LibraryAgent libraryAgent(
            @Qualifier("haikuChatModel") ChatModel haikuChatModel,
            @Qualifier("libraryMemoryProvider") ChatMemoryProvider memoryProvider,
            LibraryAgentTools tools) {
        return AiServices.builder(LibraryAgent.class)
                .chatModel(haikuChatModel)
                .chatMemoryProvider(memoryProvider)
                .tools(tools)
                .build();
    }
}
