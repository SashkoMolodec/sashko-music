package com.sashkomusic.events;

import com.sashkomusic.libraryagent.messaging.producer.dto.TrackUpdateResultDto;

public record TrackUpdateResultEvent(TrackUpdateResultDto payload) {}
