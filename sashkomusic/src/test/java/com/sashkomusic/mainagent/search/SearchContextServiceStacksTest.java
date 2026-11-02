package com.sashkomusic.mainagent.search;

import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent turn boundary: MainAgentService keeps a successful turn's stacks and rolls a failed turn's
 * back, so the stacks of cards already in the chat survive a turn that never answered.
 */
class SearchContextServiceStacksTest {

    private static final String CONVERSATION = "42";

    private final SearchContextService sut = new SearchContextService(new InMemoryChatStateStore(), Map.of());

    @Test
    void a_successful_turn_keeps_only_its_own_stacks() {
        sut.openStack(CONVERSATION, "old", request(), "old", List.of(release(1)));
        String first = sut.openStack(CONVERSATION, "new 1", request(), "new 1", List.of(release(2)));
        String second = sut.openStack(CONVERSATION, "new 2", request(), "new 2", List.of(release(3)));

        sut.retainStacks(CONVERSATION, List.of(first, second));

        assertThat(sut.getStacks(CONVERSATION)).extracting(SearchStack::id).containsExactly(first, second);
    }

    @Test
    void a_failed_turn_leaves_the_previous_stacks_and_their_active_release_alone() {
        String old = sut.openStack(CONVERSATION, "old", request(), "old", List.of(release(1), release(2)));
        sut.updateStackPage(CONVERSATION, old, 1);
        String orphan = sut.openStack(CONVERSATION, "new", request(), "new", List.of(release(3)));

        sut.discardStacks(CONVERSATION, List.of(orphan));

        assertThat(sut.getStacks(CONVERSATION)).extracting(SearchStack::id).containsExactly(old);
        assertThat(sut.getSearchResults(CONVERSATION)).extracting(ReleaseMetadata::id)
                .containsExactly("discogs:release:1", "discogs:release:2");
        assertThat(sut.getCurrentPage(CONVERSATION)).isEqualTo(1);
        assertThat(sut.getRawInput(CONVERSATION)).isEqualTo("new");
    }

    @Test
    void a_new_stack_never_takes_the_id_of_one_still_alive() {
        sut.openStack(CONVERSATION, "a", request(), "a", List.of(release(1)));
        sut.openStack(CONVERSATION, "b", request(), "b", List.of(release(2)));
        String kept = sut.openStack(CONVERSATION, "c", request(), "c", List.of(release(3)));
        sut.retainStacks(CONVERSATION, List.of(kept));

        String next = sut.openStack(CONVERSATION, "d", request(), "d", List.of(release(4)));

        assertThat(kept).isEqualTo("s3");
        assertThat(next).isEqualTo("s4");
    }

    private static MetadataSearchRequest request() {
        return new MetadataSearchRequest(null, "Burial", "Untrue", "", DateRange.empty(), "", "", "", "", "", "", "");
    }

    private static ReleaseMetadata release(int i) {
        return new ReleaseMetadata("discogs:release:" + i, null, SearchEngine.DISCOGS, "Burial", "Untrue " + i, 100,
                List.of("2007"), List.of("Album"), 0, 0, 1, List.of(), null, List.of(), null);
    }
}
