package com.sashkomusic.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(mdcPropagatingDecorator());
        // Without this, a redeploy's shutdown hook tears the pool down immediately, killing any
        // in-flight @Async listener mid-run — e.g. the Apple Music sync pipeline can finish the
        // external sync but never get to persist Track.appleMusicDbid, permanently orphaning that
        // release from later removal-sync. 55s leaves headroom under docker-compose's 60s
        // stop_grace_period for this service before SIGKILL.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(55);
        executor.initialize();
        return executor;
    }

    private TaskDecorator mdcPropagatingDecorator() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if (context != null) MDC.setContextMap(context);
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
