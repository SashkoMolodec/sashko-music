package com.sashkomusic.events;

import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadCompleteDto;

public record DownloadCompleteEvent(DownloadCompleteDto payload) {}
