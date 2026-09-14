package com.sashkomusic.mainagent.search.config;

import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.mainagent.search.SearchEngineService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    /**
     * Dedicated pool for the catalog fan-out. Deliberately NOT the shared {@code asyncExecutor}: a
     * search blocks on the futures it submits, and a thread that waits on tasks queued behind itself
     * in the same bounded pool is how thread-pool deadlocks happen. {@code CallerRunsPolicy} makes
     * saturation degrade into a sequential search instead of a rejected one.
     */
    @Bean("searchFanoutExecutor")
    public Executor searchFanoutExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 12, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "search-fanout-");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
