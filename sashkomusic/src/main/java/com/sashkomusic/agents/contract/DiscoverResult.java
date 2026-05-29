package com.sashkomusic.agents.contract;

import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;

import java.util.List;

public record DiscoverResult(
        boolean success,
        String summary,
        List<ReleaseMetadata> releases,
        SearchEngine engineUsed
) implements AgentResponse {

    public static DiscoverResult empty(String summary) {
        return new DiscoverResult(false, summary, List.of(), null);
    }

    public static DiscoverResult found(String summary, List<ReleaseMetadata> releases, SearchEngine engine) {
        return new DiscoverResult(true, summary, releases, engine);
    }
}
