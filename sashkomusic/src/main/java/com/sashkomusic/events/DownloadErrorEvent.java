package com.sashkomusic.events;

import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadErrorDto;

public record DownloadErrorEvent(DownloadErrorDto payload) {}
