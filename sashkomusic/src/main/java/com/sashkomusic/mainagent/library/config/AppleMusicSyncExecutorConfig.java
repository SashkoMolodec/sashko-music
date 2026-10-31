package com.sashkomusic.mainagent.library.config;

import com.sashkomusic.libraryagent.config.AppleMusicSyncConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@RequiredArgsConstructor
public class AppleMusicSyncExecutorConfig {

    private final AppleMusicSyncConfig config;

    /**
     * Its own bounded pool so a few multi-minute syncs can never starve {@code asyncExecutor}
     * (event listeners, Telegram flows). Fixed size = {@code applemusic.sync.max-parallel}; the
     * ffmpeg + scp work parallelises on this side, while the VM agent serialises the actual
     * iTunes {@code AddFile} calls itself.
     */
    @Bean(name = "appleMusicSyncExecutor")
    public Executor appleMusicSyncExecutor() {
        int parallel = Math.max(1, config.getMaxParallel());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallel);
        executor.setMaxPoolSize(parallel);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("am-sync-");
        // Same reasoning as asyncExecutor: a redeploy must not drop the dbid persistence step.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(55);
        executor.initialize();
        return executor;
    }
}
