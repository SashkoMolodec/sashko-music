package com.sashkomusic.downloadagent.infrastructure.client.youtubemusic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class YouTubeMusicCommandExecutor {

    public Process execute(String... command) {
        try {
            log.info("Executing: {}", String.join(" ", command));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            logOutputAsync(process);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("yt-dlp exited with code {}", exitCode);
            }
            return process;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute yt-dlp", e);
        }
    }

    private void logOutputAsync(Process process) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[yt-dlp] {}", line);
                }
            } catch (Exception e) {
                log.error("Error reading yt-dlp output: {}", e.getMessage());
            }
        });
    }
}
