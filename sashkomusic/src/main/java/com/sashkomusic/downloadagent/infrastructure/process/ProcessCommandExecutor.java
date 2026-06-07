package com.sashkomusic.downloadagent.infrastructure.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;

/**
 * Shared OS-process runner for downloader CLIs (qobuz-dl / rip / gamdl / yt-dlp / bandcamp-downloader).
 * Each downloader passes its own logTag to namespace the streamed output in logs.
 */
@Slf4j
@Component
public class ProcessCommandExecutor {

    public Process execute(String logTag, String... command) {
        try {
            log.info("Executing command [{}]: {}", logTag, String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            logOutputAsync(logTag, process);

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Command [{}] failed with exit code {}", logTag, exitCode);
            }

            return process;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute command", e);
        }
    }

    private void logOutputAsync(String logTag, Process process) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[{}] {}", logTag, line);
                }
            } catch (Exception e) {
                log.error("Error reading [{}] output: {}", logTag, e.getMessage(), e);
            }
        });
    }
}
