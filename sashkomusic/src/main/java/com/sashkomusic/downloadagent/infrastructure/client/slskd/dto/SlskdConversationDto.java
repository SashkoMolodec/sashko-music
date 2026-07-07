package com.sashkomusic.downloadagent.infrastructure.client.slskd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlskdConversationDto(
        String username,
        List<SlskdMessageDto> messages
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlskdMessageDto(
            int id,
            String timestamp,
            String username,
            String message,
            boolean acknowledged
    ) {}
}
