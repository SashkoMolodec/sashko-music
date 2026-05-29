package com.sashkomusic.events;

import com.sashkomusic.mainagent.download.messaging.dto.DownloadCancelTaskDto;

public record DownloadCancelTaskEvent(DownloadCancelTaskDto payload) {}
