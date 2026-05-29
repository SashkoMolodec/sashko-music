package com.sashkomusic.events;

import com.sashkomusic.mainagent.process.messaging.dto.ReprocessReleaseTaskDto;

public record ReprocessReleaseTaskEvent(ReprocessReleaseTaskDto payload) {}
