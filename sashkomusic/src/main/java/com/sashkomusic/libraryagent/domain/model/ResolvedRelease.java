package com.sashkomusic.libraryagent.domain.model;

import java.util.Map;

public record ResolvedRelease(
        String releaseId,
        String masterId,
        Map<String, String> fileToTrackMapping  // downloadedFilename -> originalTrackTitle
) {
}