package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;

import java.util.List;

public record SearchState(
        SearchContext context,
        List<ReleaseMetadata> releases
) {
}
