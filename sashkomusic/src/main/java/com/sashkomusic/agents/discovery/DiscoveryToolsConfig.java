package com.sashkomusic.agents.discovery;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The single-shot web-search services behind the discovery tools — no memory, no tools of their own. */
@Configuration
public class DiscoveryToolsConfig {

    /** One question in, five release names out. */
    @Bean
    public ReleaseRecommender releaseRecommender(@Qualifier("webSearchChatModel") ChatModel webSearchChatModel) {
        return AiServices.builder(ReleaseRecommender.class)
                .chatModel(webSearchChatModel)
                .build();
    }

    /** One question in, a short sourced answer out. */
    @Bean
    public MusicResearcher musicResearcher(@Qualifier("webSearchChatModel") ChatModel webSearchChatModel) {
        return AiServices.builder(MusicResearcher.class)
                .chatModel(webSearchChatModel)
                .build();
    }
}
