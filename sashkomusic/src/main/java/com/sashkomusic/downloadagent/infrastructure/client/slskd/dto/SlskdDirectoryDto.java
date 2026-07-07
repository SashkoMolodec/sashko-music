package com.sashkomusic.downloadagent.infrastructure.client.slskd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlskdDirectoryDto(
        String name,
        int fileCount,
        List<SlskdDirectoryFileDto> files
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlskdDirectoryFileDto(
            String filename,
            long size,
            Integer bitDepth,
            Integer sampleRate,
            Integer length
    ) {}
}
