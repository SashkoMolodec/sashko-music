package com.sashkomusic.downloadagent.infrastructure.client.slskd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SlskdSearchEventResponse {
    private UUID id;
    private String searchText;
    private String state;
    private Boolean isComplete;
    private Integer fileCount;
    private Integer responseCount;
    private Integer lockedFileCount;
    private Integer token;
    private Instant startedAt;
    private Instant endedAt;
    private List<Object> responses;
}
