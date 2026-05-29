package com.sashkomusic.events;

import com.sashkomusic.libraryagent.messaging.consumer.dto.TrackAnalysisCompleteDto;

public record TrackAnalysisCompleteEvent(TrackAnalysisCompleteDto payload) {}
