package com.sashkomusic.downloadagent.infrastructure.process;

import com.sashkomusic.events.DownloadLogLineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;

/**
 * Shared OS-process runner for downloader CLIs (qobuz-dl / rip / gamdl / yt-dlp / bandcamp-downloader).
 * Each downloader passes its own logTag to namespace the streamed output in logs;
 * if conversationId is non-null, each line is also published as DownloadLogLineEvent
 * (consumed by TelegramDownloadLogStreamer to forward to the user's logs topic).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessCommandExecutor {

    private final ApplicationEventPublisher eventPublisher;

    public Process execute(String logTag, String conversationId, String... command) {
        try {
            log.info("Executing command [{}]: {}", logTag, String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            logOutputAsync(logTag, conversationId, process);

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Command [{}] failed with exit code {}", logTag, exitCode);
            }

            return process;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute command", e);
        }
    }

    /**
     * Like {@link #execute}, but returns stdout instead of only logging it — for callers that
     * need to parse a command's output (e.g. the Apple Music sync script's JSON result). stdout
     * and stderr are read on separate threads so a chatty stderr can't deadlock the pipe.
     */
    public String executeCapturing(String logTag, String... command) {
        try {
            log.info("Executing command [{}]: {}", logTag, String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append('\n');
                    }
                } catch (IOException e) {
                    log.error("Error reading [{}] stdout: {}", logTag, e.getMessage(), e);
                }
            });
            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[{}] {}", logTag, line);
                    }
                } catch (IOException e) {
                    log.error("Error reading [{}] stderr: {}", logTag, e.getMessage(), e);
                }
            });
            stdoutReader.start();
            stderrReader.start();

            int exitCode = process.waitFor();
            stdoutReader.join();
            stderrReader.join();

            if (exitCode != 0) {
                log.error("Command [{}] failed with exit code {}", logTag, exitCode);
            }
            return stdout.toString();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute command", e);
        }
    }

    private void logOutputAsync(String logTag, String conversationId, Process process) {
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isNoisyProgressLine(line)) {
                        log.debug("[{}] {}", logTag, line);
                        continue;
                    }
                    log.info("[{}] {}", logTag, line);
                    if (conversationId != null && !line.isBlank()) {
                        eventPublisher.publishEvent(new DownloadLogLineEvent(conversationId, logTag, line));
                    }
                }
            } catch (Exception e) {
                log.error("Error reading [{}] output: {}", logTag, e.getMessage(), e);
            }
        });
    }

    /**
     * bandcamp-dl repaints its progress bar via a bare '\r' with no trailing '\n'
     * (see its print_clean helper) — BufferedReader.readLine() treats a lone '\r' as a line
     * terminator too, so every one of the ~100 chunk-progress repaints per track arrives here as
     * its own "line" (e.g. "(3/12) [==   ] :: Downloading: 03. artist - title"), flooding the
     * Telegram logs topic far faster than TelegramDownloadLogStreamer's 10s batching can absorb.
     * Downgraded the same way yt-dlp's ETA/frag progress ticks already are.
     */
    private boolean isNoisyProgressLine(String line) {
        if (line.startsWith("[download]") && (line.contains("ETA") || line.contains("frag "))) {
            return true;
        }
        return line.contains(") [") && line.contains("] :: Downloading:");
    }
}
