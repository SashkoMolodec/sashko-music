package com.sashkomusic.mainagent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sashkomusic.shared.model.ReleaseMetadata;

import java.util.List;

/**
 * @param context  the active stack's query context (source, request, raw input, page)
 * @param releases the active stack's releases — mirrors the last entry of {@code stacks}
 * @param stacks   every stack built during the last turn; absent in payloads written before
 *                 multi-stack results existed, hence the null-normalising constructor
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchState(
        SearchContext context,
        List<ReleaseMetadata> releases,
        List<SearchStack> stacks
) {
    public SearchState {
        releases = releases == null ? List.of() : releases;
        stacks = stacks == null ? List.of() : stacks;
    }

    public SearchState(SearchContext context, List<ReleaseMetadata> releases) {
        this(context, releases, List.of());
    }
}
