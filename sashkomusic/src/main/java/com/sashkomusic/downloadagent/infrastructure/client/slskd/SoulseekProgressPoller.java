package com.sashkomusic.downloadagent.infrastructure.client.slskd;

import com.sashkomusic.events.DownloadBatchCompleteEvent;
import com.sashkomusic.events.DownloadLogLineEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls slskd HTTP API once per minute for active soulseek downloads and emits a
 * DownloadLogLineEvent with aggregated progress (X/N files, MB downloaded, slowest speed).
 * Other download CLIs surface progress via stdout — soulseek doesn't, so we poll instead.
 *
 * Lifecycle: SlskdClient calls register() right after enqueueing transfers; the poller
 * removes the entry when all files are done, or when a batch-complete / error event arrives.
 */
@Component
@Slf4j
public class SoulseekProgressPoller {

    private static final long POLL_INTERVAL_MS = 60_000;
    private static final Set<String> COMPLETED_STATES = Set.of(
            "completed, succeeded", "completed, cancelled", "completed, errored",
            "completed, timedout", "completed, rejected");

    private final RestClient client;
    private final String apiKey;
    private final ApplicationEventPublisher events;
    private final Map<String, Active> tracked = new ConcurrentHashMap<>();

    public SoulseekProgressPoller(RestClient.Builder builder,
                                  @Value("${slskd.api-key:}") String apiKey,
                                  @Value("${slskd.base-url:http://localhost:5030}") String baseUrl,
                                  ApplicationEventPublisher events) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.events = events;
    }

    public void register(String releaseId, String conversationId, String username, List<String> transferIds) {
        if (conversationId == null) return; // no log target
        tracked.put(releaseId, new Active(releaseId, conversationId, username, Set.copyOf(transferIds)));
        log.info("Tracking soulseek progress for releaseId={}, transfers={}", releaseId, transferIds.size());
    }

    public void unregister(String releaseId) {
        if (tracked.remove(releaseId) != null) {
            log.info("Stopped tracking soulseek progress for releaseId={}", releaseId);
        }
    }

    @EventListener
    public void onBatchComplete(DownloadBatchCompleteEvent event) {
        unregister(event.payload().releaseId());
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS, initialDelay = POLL_INTERVAL_MS)
    public void pollAll() {
        if (tracked.isEmpty()) return;
        for (Active a : tracked.values()) {
            try {
                pollOne(a);
            } catch (Exception e) {
                log.warn("Failed to poll slskd progress for releaseId={}: {}", a.releaseId, e.getMessage());
            }
        }
    }

    private void pollOne(Active a) {
        List<Map<String, Object>> dirs = fetchUserDownloads(a.username);
        if (dirs == null) return;

        int total = a.transferIds.size();
        int done = 0;
        long totalSize = 0;
        long totalDone = 0;
        String slowestState = null;
        double slowestSpeed = Double.MAX_VALUE;

        for (Map<String, Object> dir : dirs) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) dir.get("files");
            if (files == null) continue;
            for (Map<String, Object> f : files) {
                String id = (String) f.get("id");
                if (!a.transferIds.contains(id)) continue;

                long size = asLong(f.get("size"));
                long bytes = asLong(f.get("bytesTransferred"));
                String state = String.valueOf(f.getOrDefault("state", "")).toLowerCase();
                double speed = asDouble(f.get("averageSpeed"));

                totalSize += size;
                totalDone += bytes;
                if (isCompleted(state)) {
                    done++;
                } else if (speed >= 0 && speed < slowestSpeed) {
                    slowestSpeed = speed;
                    slowestState = state;
                }
            }
        }

        double mbDone = totalDone / (1024.0 * 1024.0);
        double mbTotal = totalSize / (1024.0 * 1024.0);
        String line = "soulseek %s — %d/%d files, %.1f / %.1f MB%s".formatted(
                a.releaseId, done, total, mbDone, mbTotal,
                slowestState != null ? " (slowest: " + slowestState + ")" : "");
        events.publishEvent(new DownloadLogLineEvent(a.conversationId, "slskd-progress", line));

        if (done == total && total > 0) {
            unregister(a.releaseId);
        }
    }

    private List<Map<String, Object>> fetchUserDownloads(String username) {
        return client.get()
                .uri("/api/v0/transfers/downloads/{username}", username)
                .header("X-API-KEY", apiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    private static boolean isCompleted(String state) {
        return COMPLETED_STATES.stream().anyMatch(state::contains);
    }

    private static long asLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static double asDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private record Active(String releaseId, String conversationId, String username, Set<String> transferIds) {}
}
