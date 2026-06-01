package com.sashkomusic.config;

import com.sashkomusic.agents.discovery.SearchRequestExtractor;
import com.sashkomusic.mainagent.bot.newtopic.TopicNameGenerator;
import com.sashkomusic.mainagent.download.DownloadBatchAnalyzer;
import com.sashkomusic.mainagent.process.MetadataSuggester;
import com.sashkomusic.libraryagent.domain.service.processFolder.FolderNameParser;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stateless extractors backed by the cheap Haiku model.
 * Each one owns a single narrow prompt — no shared interface.
 */
@Configuration
public class AiExtractorsConfig {

    @Bean
    public SearchRequestExtractor searchRequestExtractor(@Qualifier("haikuChatModel") ChatModel haiku) {
        return AiServices.builder(SearchRequestExtractor.class).chatModel(haiku).build();
    }

    @Bean
    public DownloadBatchAnalyzer downloadBatchAnalyzer(@Qualifier("haikuChatModel") ChatModel haiku) {
        return AiServices.builder(DownloadBatchAnalyzer.class).chatModel(haiku).build();
    }

    @Bean
    public FolderNameParser folderNameParser(@Qualifier("haikuChatModel") ChatModel haiku) {
        return AiServices.builder(FolderNameParser.class).chatModel(haiku).build();
    }

    @Bean
    public MetadataSuggester metadataSuggester(@Qualifier("haikuChatModel") ChatModel haiku) {
        return AiServices.builder(MetadataSuggester.class).chatModel(haiku).build();
    }

    @Bean
    public TopicNameGenerator topicNameGenerator(@Qualifier("haikuChatModel") ChatModel haiku) {
        return AiServices.builder(TopicNameGenerator.class).chatModel(haiku).build();
    }
}
