package com.sashkomusic.mainagent.search.config;

import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class SearchEngineConfig {

    @Bean
    public Map<SearchEngine, SearchEngineService> searchEngines(List<SearchEngineService> services) {
        return services.stream()
                .collect(Collectors.toMap(
                        SearchEngineService::getSource,
                        service -> service
                ));
    }
}