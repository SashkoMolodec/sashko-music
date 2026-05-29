package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;

import java.util.List;

public record SearchContext(
        SearchEngine source,
        MetadataSearchRequest request,
        String rawInput,
        List<String> releaseIds
) {
}