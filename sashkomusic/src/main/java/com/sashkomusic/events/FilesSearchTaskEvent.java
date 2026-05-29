package com.sashkomusic.events;

import com.sashkomusic.mainagent.download.messaging.dto.SearchFilesTaskDto;

public record FilesSearchTaskEvent(SearchFilesTaskDto payload) {}
