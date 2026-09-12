package com.sashkomusic.libraryagent.api.dto;

/** Component names are the JSON keys the Python analyzer sends — renaming them breaks the wire format. */
public record TrackAnalysisCompleteRequest(
        Long trackId,
        String jsonResultPath,
        boolean success,
        String errorMessage
) {
}
