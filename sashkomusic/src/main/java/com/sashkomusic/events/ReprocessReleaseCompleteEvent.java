package com.sashkomusic.events;

import com.sashkomusic.libraryagent.messaging.producer.dto.ReprocessReleaseResultDto;

public record ReprocessReleaseCompleteEvent(ReprocessReleaseResultDto payload) {}
