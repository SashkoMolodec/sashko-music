package com.sashkomusic.downloadagent.infrastructure.client.slskd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.events.DownloadBatchCompleteEvent;
import com.sashkomusic.events.DownloadLogLineEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
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
 *
 * slskd's /api/v0/transfers/downloads/{username} returns either a single user object or a list of
 * them depending on version, so we parse via JsonNode and walk both shapes.
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
    private final ObjectMapper mapper;
    private final Map<String, Active> tracked = new ConcurrentHashMap<>();

    public SoulseekProgressPoller(RestClient.Builder builder,
                                  @Value("${slskd.api-key:}") String apiKey,
                                  @Value("${slskd.base-url:http://localhost:5030}") String baseUrl,
                                  ApplicationEventPublisher events,
                                  ObjectMapper mapper) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.events = events;
        this.mapper = mapper;
    }

    public void register(String releaseId, String conversationId, String username, List<String> transferIds) {
        if (conversationId == null) return;
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

    private void pollOne(Active a) throws Exception {
        String json = fetchRaw(a.username);
        if (json == null || json.isBlank()) return;
        JsonNode root = mapper.readTree(json);

        int total = a.transferIds.size();
        int done = 0;
        long totalSize = 0;
        long totalDone = 0;
        String slowestState = null;
        double slowestSpeed = Double.MAX_VALUE;

        for (JsonNode userNode : usersOf(root)) {
            for (JsonNode dirNode : arrayOf(userNode.path("directories"))) {
                for (JsonNode file : arrayOf(dirNode.path("files"))) {
                    String id = file.path("id").asText("");
                    if (!a.transferIds.contains(id)) continue;

                    long size = file.path("size").asLong(0);
                    long bytes = file.path("bytesTransferred").asLong(0);
                    String state = file.path("state").asText("").toLowerCase();
                    double speed = file.path("averageSpeed").asDouble(0);

                    totalSize += size;
                    totalDone += bytes;
                    if (isCompleted(state)) {
                        done++;
                    } else if (speed < slowestSpeed) {
                        slowestSpeed = speed;
                        slowestState = state;
                    }
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

    private String fetchRaw(String username) {
        return client.get()
                .uri("/api/v0/transfers/downloads/{username}", username)
                .header("X-API-KEY", apiKey)
                .retrieve()
                .body(String.class);
    }

    /** slskd 0.x sometimes returns the single user object directly; 1.x wraps it in an array. */
    private static Iterable<JsonNode> usersOf(JsonNode root) {
        return root.isArray() ? root : List.of(root);
    }

    private static Iterable<JsonNode> arrayOf(JsonNode node) {
        return node.isArray() ? node : List.of();
    }

    private static boolean isCompleted(String state) {
        return COMPLETED_STATES.stream().anyMatch(state::contains);
    }

    private record Active(String releaseId, String conversationId, String username, Set<String> transferIds) {}
}
