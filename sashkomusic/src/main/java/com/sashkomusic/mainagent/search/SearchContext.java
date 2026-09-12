package com.sashkomusic.mainagent.search;

import com.sashkomusic.shared.model.SearchEngine;
import com.sashkomusic.shared.model.MetadataSearchRequest;

import java.util.List;

public record SearchContext(
        SearchEngine source,
        MetadataSearchRequest request,
        String rawInput,
        List<String> releaseIds,
        int currentPage
) {
}