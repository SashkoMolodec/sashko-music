package com.sashkomusic.agents.discovery;

import com.sashkomusic.agents.discovery.RelatedArtistsCollector.Connection;
import com.sashkomusic.mainagent.search.SearchStack;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryAgentServiceTest {

    private static final String PEOPLE = Connection.DIRECT.describe("Drexciya");
    private static final String BRIDGES = Connection.BRIDGE.describe("Drexciya");
    private static final String LISTENERS = Connection.CO_LISTEN.describe("Drexciya");

    @Test
    void one_stack_stays_a_plain_aggregate_with_no_rules_for_the_intro() {
        String summary = DiscoveryAgentService.formatForMainAgent(List.of(stack("s1", "Burial", null)));

        assertThat(summary).startsWith("1 releases").doesNotContain("Use only the facts");
    }

    @Test
    void similar_stacks_are_listed_by_group_however_they_were_woven_on_screen() {
        String summary = DiscoveryAgentService.formatForMainAgent(List.of(
                stack("s1", "Arpanet — Wireless Internet · псевдонім Gerald Donald", PEOPLE),
                stack("s2", "Jeff Mills — Waveform Transmission · разом у Underground Resistance", BRIDGES),
                stack("s3", "Elecktroids — Elektroworld · James Stinson у складі", PEOPLE),
                stack("s4", "Aphex Twin — Selected Ambient Works · часто слухають разом", LISTENERS)));

        assertThat(summary).containsSubsequence(
                "[" + PEOPLE + "]", "• Arpanet", "• Elecktroids",
                "[" + BRIDGES + "]", "• Jeff Mills",
                "[" + LISTENERS + "]", "• Aphex Twin");
    }

    @Test
    void a_grouped_intro_gets_more_room_and_must_stay_within_the_listed_facts() {
        String summary = DiscoveryAgentService.formatForMainAgent(List.of(
                stack("s1", "Arpanet — Wireless Internet", PEOPLE),
                stack("s2", "Jeff Mills — Waveform Transmission", BRIDGES)));

        assertThat(summary).contains("at most 900 characters", "no markdown", "Use only the facts above");
    }

    @Test
    void a_stack_with_no_group_is_not_lost_next_to_grouped_ones() {
        String summary = DiscoveryAgentService.formatForMainAgent(List.of(
                stack("s1", "Burial — Untrue", null),
                stack("s2", "Arpanet — Wireless Internet", PEOPLE)));

        assertThat(summary).contains("[other picks in this answer]\n• Burial — Untrue");
    }

    @Test
    void ungrouped_stacks_keep_their_short_intro_and_still_may_not_invent_facts() {
        String summary = DiscoveryAgentService.formatForMainAgent(IntStream.range(0, 4)
                .mapToObj(i -> stack("s" + i, "Tresor", null))
                .toList());

        assertThat(summary)
                .contains("without naming each one", "Use only the facts above")
                .doesNotContain("[", "at most 900");
    }

    private static SearchStack stack(String id, String label, String group) {
        ReleaseMetadata release = new ReleaseMetadata("discogs:release:" + id, null, SearchEngine.DISCOGS, "Artist",
                "Title", 100, List.of("1995"), List.of("Album"), 0, 0, 1, List.of(), null, List.of("Electro"), "Tresor");
        return new SearchStack(id, label, group, List.of(release), 0);
    }
}
