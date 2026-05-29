package com.sashkomusic.events;

import com.sashkomusic.libraryagent.messaging.producer.dto.LibraryProcessingCompleteDto;

public record LibraryProcessingCompleteEvent(LibraryProcessingCompleteDto payload) {}
