package com.sashkomusic.mainagent.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sashkomusic.shared.model.ReleaseMetadata;

import java.util.List;

/**
 * One independently browsable pile of release cards.
 * <p>
 * A turn can produce several: "порадь щось схоже" answers with one stack per recommended release,
 * each scrolled with its own ⬅️/➡️ on its own message. {@code id} is what the {@code CARD:} callback
 * carries, {@code label} is the query that built the stack (shown on the card so three stacks in a
 * row stay distinguishable). {@code group} says what kind of pick this is, for the intro only; most
 * stacks have none.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchStack(
        String id,
        String label,
        String group,
        List<ReleaseMetadata> releases,
        int currentPage
) {
    public SearchStack {
        releases = releases == null ? List.of() : releases;
    }

    public SearchStack withPage(int page) {
        return new SearchStack(id, label, group, releases, page);
    }
}
