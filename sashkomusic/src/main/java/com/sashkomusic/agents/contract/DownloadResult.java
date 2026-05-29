package com.sashkomusic.agents.contract;

public record DownloadResult(
        boolean success,
        String summary
) implements AgentResponse {

    public static DownloadResult started(String summary) {
        return new DownloadResult(true, summary);
    }

    public static DownloadResult failed(String summary) {
        return new DownloadResult(false, summary);
    }
}
