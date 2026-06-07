package com.sashkomusic.events;

/** Published by ProcessCommandExecutor for each CLI output line; consumed by log streamer. */
public record DownloadLogLineEvent(String conversationId, String tag, String line) {
}
