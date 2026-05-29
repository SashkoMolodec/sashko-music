package com.sashkomusic.events;

import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadBatchCompleteDto;

public record DownloadBatchCompleteEvent(DownloadBatchCompleteDto payload) {}
