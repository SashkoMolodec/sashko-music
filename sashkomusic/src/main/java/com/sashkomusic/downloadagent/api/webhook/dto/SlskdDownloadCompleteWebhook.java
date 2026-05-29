package com.sashkomusic.downloadagent.api.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlskdDownloadCompleteWebhook(
        @JsonProperty("type")
        String type,

        @JsonProperty("version")
        int version,

        @JsonProperty("localFilename")
        String localFilename,

        @JsonProperty("remoteFilename")
        String remoteFilename,

        @JsonProperty("transfer")
        Transfer transfer,

        @JsonProperty("id")
        String id,

        @JsonProperty("timestamp")
        String timestamp
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transfer(
            @JsonProperty("id")
            String id,

            @JsonProperty("username")
            String username,

            @JsonProperty("direction")
            String direction,

            @JsonProperty("filename")
            String filename,

            @JsonProperty("size")
            long size,

            @JsonProperty("startOffset")
            long startOffset,

            @JsonProperty("state")
            String state,

            @JsonProperty("stateDescription")
            String stateDescription,

            @JsonProperty("requestedAt")
            String requestedAt,

            @JsonProperty("enqueuedAt")
            String enqueuedAt,

            @JsonProperty("startedAt")
            String startedAt,

            @JsonProperty("endedAt")
            String endedAt,

            @JsonProperty("bytesTransferred")
            long bytesTransferred,

            @JsonProperty("averageSpeed")
            double averageSpeed,

            @JsonProperty("bytesRemaining")
            long bytesRemaining,

            @JsonProperty("elapsedTime")
            String elapsedTime,

            @JsonProperty("percentComplete")
            double percentComplete,

            @JsonProperty("remainingTime")
            String remainingTime
    ) {
    }
}