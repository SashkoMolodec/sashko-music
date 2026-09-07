package com.sashkomusic.mainagent.library.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.events.AppleMusicSyncCompleteEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * applemusic_sync.py prints one JSON blob with a dbid per successfully-added track.
 * Shared by every caller of the script — the automatic post-download sync and the
 * manual "🍎" now-playing button — so a dbid captured either way is persisted the
 * same way and can later be used to mirror a deletion.
 */
@Slf4j
public final class AppleMusicSyncOutputParser {

    private AppleMusicSyncOutputParser() {
    }

    public static List<AppleMusicSyncCompleteEvent.TrackDbid> parse(ObjectMapper objectMapper, String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<AppleMusicSyncCompleteEvent.TrackDbid> mappings = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(output);
            for (JsonNode trackNode : root.path("tracks")) {
                JsonNode dbidNode = trackNode.path("result").path("dbid");
                if (dbidNode.isNumber()) {
                    mappings.add(new AppleMusicSyncCompleteEvent.TrackDbid(
                            trackNode.path("source").asText(), dbidNode.asLong()));
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Apple Music sync output: {}", e.getMessage());
        }
        return mappings;
    }
}
