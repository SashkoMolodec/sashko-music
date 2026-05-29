package com.sashkomusic.events;

import com.sashkomusic.downloadagent.messaging.producer.dto.SearchFilesResultDto;

public record FileSearchResultEvent(SearchFilesResultDto payload) {}
