package com.sashkomusic.downloadagent.infrastructure.client.qobuz;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class QobuzCommandExecutor {

    public Process execute(String... command) {
        try {
            log.info("Executing command: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            logOutputAsync(process);

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Command failed with exit code {}", exitCode);
            }

            return process;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute command", e);
        }
    }

    private void logOutputAsync(Process process) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[qobuz-dl] {}", line);
                }
            } catch (Exception e) {
                log.error("Error reading output: {}", e.getMessage(), e);
            }
        });
    }
}
