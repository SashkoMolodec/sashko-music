package com.sashkomusic.agents.library;

import com.sashkomusic.agents.turn.AgentTurn;
import com.sashkomusic.libraryagent.domain.model.LibrarySearchResult;
import com.sashkomusic.libraryagent.domain.service.LibrarySearchService;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.LastReleaseContextHolder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchOwnLibraryTest {

    private final LibrarySearchService searchService = mock(LibrarySearchService.class);
    private final LastReleaseContextHolder lastRelease = mock(LastReleaseContextHolder.class);
    private final LibraryAgentTools tools = new LibraryAgentTools(
            null, searchService, null, lastRelease, null, null, null, null, null, null, null, null);
    private final AgentTurn turn = new AgentTurn(ConversationContext.dm(42L), text -> { });

    @Test
    void lists_markers_apart_from_genres() {
        when(searchService.search("detroit techno", 10)).thenReturn(List.of(
                new LibrarySearchResult(169L, "Deep Space", "Model 500", 1995,
                        "electro, techno", "detroit techno", "/lib/vault/model 500/deep space", 9, 0.6),
                new LibrarySearchResult(35L, "Dreamcore EP", "Introversion", 2024,
                        "detroit, techno", null, "/lib/working/introversion/dreamcore", 4, 0.7)));

        String reply = tools.searchOwnLibrary("detroit techno", turn.parameters());

        assertThat(reply).contains("Знайдено у твоїй бібліотеці (2)")
                .contains("Model 500 — Deep Space (1995) [electro, techno] {мітки: detroit techno}, 9 треків")
                .contains("Introversion — Dreamcore EP (2024) [detroit, techno], 4 треків");
        verify(lastRelease).set(turn.conversationId(), 169L, "Deep Space", "Model 500");
    }

    @Test
    void reports_an_empty_library_hit() {
        when(searchService.search("acid", 10)).thenReturn(List.of());

        assertThat(tools.searchOwnLibrary("acid", turn.parameters()))
                .isEqualTo("нічого не знайдено у твоїй бібліотеці за запитом: acid");
    }
}
