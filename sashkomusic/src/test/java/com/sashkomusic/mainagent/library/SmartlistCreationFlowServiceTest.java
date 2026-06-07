package com.sashkomusic.mainagent.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistDraft;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistDsl;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistDslExtractor;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.state.InMemoryChatStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmartlistCreationFlowServiceTest {

    private final ConversationContext ctx = ConversationContext.dm(42L);
    private InMemoryChatStateStore stateStore;
    private SmartlistService smartlistService;
    private SmartlistDslExtractor extractor;
    private SmartlistCreationFlowService sut;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryChatStateStore();
        smartlistService = mock(SmartlistService.class);
        extractor = mock(SmartlistDslExtractor.class);
        sut = new SmartlistCreationFlowService(smartlistService, extractor, stateStore, new ObjectMapper());
    }

    @Test
    void startCreate_extracts_dsl_stores_draft_and_returns_preview_card() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(
                new SmartlistDsl.ContainsCondition("genre", "house"),
                new SmartlistDsl.RangeCondition("rating", 4, 5)
        ));
        when(extractor.extractJson(anyString(), anyString())).thenReturn("{}");
        when(smartlistService.parse("{}")).thenReturn(dsl);
        when(smartlistService.describe(dsl)).thenReturn("genre contains \"house\" AND rating 4…5");
        when(smartlistService.previewTracks(any(), anyInt())).thenReturn(List.of(track("track A", "Artist")));

        SmartlistCreationFlowService.StartResult result =
                sut.startCreate(ctx, "house 4plus", "house tracks rating 4+");

        assertThat(result.drafted()).isTrue();
        assertThat(result.agentSummary()).contains("показав картку", "house 4plus");
        assertThat(sut.hasDraft(ctx)).isTrue();
        Optional<SmartlistDraft> stored = stateStore.get(ctx.conversationId(), SmartlistDraft.FLOW_KEY, SmartlistDraft.class);
        assertThat(stored).isPresent();
        assertThat(stored.get().name()).isEqualTo("house 4plus");
        assertThat(result.responses()).hasSize(1);
        BotResponse card = result.responses().get(0);
        assertThat(card.text()).contains("house 4plus", "genre contains", "Artist", "track A");
        assertThat(card.buttons()).containsKeys("✅ створити", "❌ скасувати");
        assertThat(card.buttons().values()).contains("SM:OK", "SM:NO");
    }

    @Test
    void startCreate_returns_failure_summary_when_extractor_output_unparseable() {
        when(extractor.extractJson(anyString(), anyString())).thenReturn("garbage");
        when(smartlistService.parse("garbage")).thenThrow(new IllegalStateException("Failed to parse smartlist DSL"));

        SmartlistCreationFlowService.StartResult result =
                sut.startCreate(ctx, "x", "some rule");

        assertThat(result.drafted()).isFalse();
        assertThat(result.agentSummary()).startsWith("не створено");
        assertThat(result.responses()).singleElement()
                .satisfies(r -> assertThat(r.text()).contains("не зміг розібрати"));
        assertThat(sut.hasDraft(ctx)).isFalse();
    }

    @Test
    void startCreate_returns_failure_when_evaluator_rejects_dsl() {
        SmartlistDsl bogus = new SmartlistDsl(List.of(
                new SmartlistDsl.RangeCondition("genre", 1, 5) // range on text field — unsupported
        ));
        when(extractor.extractJson(anyString(), anyString())).thenReturn("{}");
        when(smartlistService.parse("{}")).thenReturn(bogus);
        when(smartlistService.previewTracks(any(), anyInt()))
                .thenThrow(new IllegalArgumentException("range op is not supported on field 'genre'"));

        SmartlistCreationFlowService.StartResult result =
                sut.startCreate(ctx, "x", "some rule");

        assertThat(result.drafted()).isFalse();
        assertThat(sut.hasDraft(ctx)).isFalse();
        assertThat(result.responses().get(0).text()).contains("не зміг розібрати");
    }

    @Test
    void confirm_creates_smartlist_and_clears_draft() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(new SmartlistDsl.ContainsCondition("genre", "house")));
        stateStore.put(ctx.conversationId(), SmartlistDraft.FLOW_KEY, new SmartlistDraft("hl", dsl));
        when(smartlistService.create("hl", dsl)).thenReturn(new SmartlistService.SmartlistSummary(1L, "hl", 3, "genre contains \"house\""));

        List<BotResponse> responses = sut.handleConfirm(ctx, "SM:OK");

        verify(smartlistService).create("hl", dsl);
        assertThat(sut.hasDraft(ctx)).isFalse();
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).text()).contains("створено", "3 треків");
    }

    @Test
    void cancel_clears_draft() {
        stateStore.put(ctx.conversationId(), SmartlistDraft.FLOW_KEY,
                new SmartlistDraft("hl", new SmartlistDsl(List.of(new SmartlistDsl.ContainsCondition("genre", "house")))));

        List<BotResponse> responses = sut.handleCancel(ctx, "SM:NO");

        assertThat(sut.hasDraft(ctx)).isFalse();
        assertThat(responses.get(0).text()).contains("скасовано");
    }

    @Test
    void refine_text_ok_confirms_creation() {
        SmartlistDsl dsl = new SmartlistDsl(List.of(new SmartlistDsl.ContainsCondition("genre", "house")));
        stateStore.put(ctx.conversationId(), SmartlistDraft.FLOW_KEY, new SmartlistDraft("hl", dsl));
        when(smartlistService.create("hl", dsl)).thenReturn(new SmartlistService.SmartlistSummary(1L, "hl", 1, "..."));

        List<BotResponse> responses = sut.refine(ctx, "ок");

        verify(smartlistService).create("hl", dsl);
        assertThat(responses.get(0).text()).contains("створено");
    }

    private Track track(String title, String artistName) {
        Track t = new Track(title, 1);
        Set<Artist> artists = new HashSet<>();
        artists.add(new Artist(artistName));
        t.setArtists(artists);
        return t;
    }
}
