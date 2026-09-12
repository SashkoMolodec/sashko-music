package com.sashkomusic.libraryagent.client.dto;

/** Component names are the JSON keys the Python analyzer reads — renaming them breaks the wire format. */
public record AnalyzeTrackRequest(
        Long trackId,
        String localPath,
        Long releaseId,
        String releaseTitle,
        String trackTitle
) {
}
