package com.sashkomusic.events;

import com.sashkomusic.mainagent.process.messaging.dto.ProcessLibraryTaskDto;

public record ProcessLibraryTaskEvent(ProcessLibraryTaskDto payload) {}
