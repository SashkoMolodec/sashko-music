package com.sashkomusic.agents.main;

import com.sashkomusic.agents.config.MainChatMemoryProvider;
import com.sashkomusic.agents.discovery.DiscoveryTools;
import com.sashkomusic.agents.discovery.MusicResearcher;
import com.sashkomusic.agents.discovery.RelatedArtistsCollector;
import com.sashkomusic.agents.discovery.ReleaseRecommender;
import com.sashkomusic.mainagent.search.SearchRequestExtractor;
import com.sashkomusic.agents.library.LibraryAgentService;
import com.sashkomusic.agents.turn.ProgressNotifier;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.MusicDownloadFlowService;
import com.sashkomusic.mainagent.search.AggregatedSearchService;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.search.SearchContextService;
import com.sashkomusic.mainagent.search.client.musicbrainz.MusicBrainzClient;
import com.sashkomusic.shared.model.DateRange;
import com.sashkomusic.shared.model.Language;
import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MainAgent wired the way {@link MainAgentConfig} wires it — real AiServices, real tool objects —
 * against a scripted model, so the turn plumbing is checked without the network: the turn reaches
 * every tool through InvocationParameters, stays out of the tool schemas, and the reply comes out
 * as intro, then shown cards, then stack cards.
 */
class MainAgentServiceTest {

    private static final ConversationContext CTX = ConversationContext.topic(-100L, 7);

    private final MusicDownloadFlowService downloads = mock(MusicDownloadFlowService.class);
    private final LibraryAgentService library = mock(LibraryAgentService.class);
    private final AggregatedSearchService aggregatedSearch = mock(AggregatedSearchService.class);
    private final SearchContextService searchContext = mock(SearchContextService.class);
    private final SearchRequestExtractor extractor = mock(SearchRequestExtractor.class);
    private final ReleaseSearchFlowService releaseSearch = mock(ReleaseSearchFlowService.class);
    private final ProgressNotifier progress = mock(ProgressNotifier.class);
    private final MainChatMemoryProvider memory = mock(MainChatMemoryProvider.class);

    private final ScriptedModel model = new ScriptedModel();
    private final MainAgentService sut = new MainAgentService(agent(), memory, progress, releaseSearch, searchContext);

    @Test
    void the_turn_never_shows_up_in_a_tool_schema() {
        model.reply("привіт");

        sut.chat(CTX, "привіт");

        List<ToolSpecification> tools = model.requests.getFirst().toolSpecifications();
        assertThat(tools).extracting(ToolSpecification::name).containsExactlyInAnyOrder(
                "manageLibrary", "downloadMusic",
                "search", "exploreAndRecommend", "findSimilar", "digDeeper", "getTrackList", "researchMusic");
        ToolSpecification download = tools.stream().filter(t -> t.name().equals("downloadMusic")).findFirst().orElseThrow();
        assertThat(download.parameters().properties()).containsOnlyKeys("artist", "album");
        ToolSpecification digDeeper = tools.stream().filter(t -> t.name().equals("digDeeper")).findFirst().orElseThrow();
        assertThat(digDeeper.parameters() == null || digDeeper.parameters().properties().isEmpty()).isTrue();
    }

    @Test
    void a_download_card_follows_the_agents_intro() {
        BotResponse options = BotResponse.text("варіанти завантаження");
        when(downloads.getDownloadOptions(CTX, "Burial Untrue")).thenReturn(List.of(options));
        model.callTool("downloadMusic", "{\"artist\":\"Burial\",\"album\":\"Untrue\"}");
        model.reply("ось що є для untrue");

        List<BotResponse> out = sut.chat(CTX, "скачай burial untrue");

        assertThat(out).containsExactly(BotResponse.aiText("ось що є для untrue"), options);
        assertThat(model.lastToolResult()).isEqualTo("варіанти завантаження");
        verify(progress).notify(CTX, "⬇️ качаю...");
    }

    @Test
    void a_search_opens_its_stack_in_the_main_conversation_and_the_stack_card_comes_last() {
        when(extractor.extract(anyString())).thenReturn(MetadataSearchRequest.create("Burial", "Untrue", "",
                DateRange.empty(), "", "", "", "", "", "", "", Language.EN));
        ReleaseMetadata untrue = new ReleaseMetadata("discogs:release:1", null, SearchEngine.DISCOGS, "Burial",
                "Untrue", 100, List.of("2007"), List.of("Album"), 0, 0, 1, List.of(), null, List.of("Dubstep"), "Hyperdub");
        when(aggregatedSearch.search(any())).thenReturn(
                new AggregatedSearchService.Aggregated(List.of(untrue), 1, List.of(SearchEngine.DISCOGS), 0));
        when(searchContext.openStack(eq(CTX.conversationId()), anyString(), any(), any(), any(), any())).thenReturn("s1");
        BotResponse stackCard = BotResponse.text("картка untrue");
        when(releaseSearch.buildStackResponse(CTX, "s1", 0)).thenReturn(List.of(stackCard));
        model.callTool("search", "{\"query\":\"Burial Untrue\"}");
        model.reply("untrue — другий альбом burial");

        List<BotResponse> out = sut.chat(CTX, "знайди burial untrue");

        assertThat(out).containsExactly(BotResponse.aiText("untrue — другий альбом burial"), stackCard);
        verify(searchContext).retainStacks(CTX.conversationId(), List.of("s1"));
        verify(searchContext, never()).beginStacks(any());
        verify(searchContext, never()).discardStacks(any(), any());
        assertThat(model.lastToolResult()).startsWith("showed 1 card stack for 'Burial Untrue'").contains("Hyperdub");
    }

    @Test
    void the_library_agent_works_in_the_same_turn() {
        BotResponse confirm = BotResponse.text("видалити untrue?");
        when(library.handle(any(), eq("видали untrue"))).thenAnswer(invocation -> {
            com.sashkomusic.agents.turn.AgentTurn turn = invocation.getArgument(0);
            assertThat(turn.conversationId()).isEqualTo(CTX.conversationId());
            turn.show(List.of(confirm));
            return "показав картку підтвердження видалення";
        });
        model.callTool("manageLibrary", "{\"command\":\"видали untrue\"}");
        model.reply("перевір і підтверди");

        List<BotResponse> out = sut.chat(CTX, "видали untrue");

        assertThat(out).containsExactly(BotResponse.aiText("перевір і підтверди"), confirm);
    }

    @Test
    void every_turn_compacts_the_memory_before_the_model_sees_it() {
        model.reply("привіт");

        sut.chat(CTX, "привіт");

        verify(memory).compact(CTX.conversationId());
        verify(searchContext, never()).retainStacks(any(), any());
    }

    @Test
    void a_failed_turn_rolls_back_its_stacks_and_leaves_the_older_ones() {
        when(extractor.extract(anyString())).thenReturn(MetadataSearchRequest.create("Burial", "Untrue", "",
                DateRange.empty(), "", "", "", "", "", "", "", Language.EN));
        ReleaseMetadata untrue = new ReleaseMetadata("discogs:release:1", null, SearchEngine.DISCOGS, "Burial",
                "Untrue", 100, List.of("2007"), List.of("Album"), 0, 0, 1, List.of(), null, List.of("Dubstep"), "Hyperdub");
        when(aggregatedSearch.search(any())).thenReturn(
                new AggregatedSearchService.Aggregated(List.of(untrue), 1, List.of(SearchEngine.DISCOGS), 0));
        when(searchContext.openStack(eq(CTX.conversationId()), anyString(), any(), any(), any(), any())).thenReturn("s4");
        model.callTool("search", "{\"query\":\"Burial Untrue\"}");
        model.fail(new IllegalStateException("model overloaded"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.chat(CTX, "знайди burial untrue"))
                .hasMessageContaining("model overloaded");

        verify(searchContext).discardStacks(CTX.conversationId(), List.of("s4"));
        verify(searchContext, never()).retainStacks(any(), any());
        verify(searchContext, never()).beginStacks(any());
    }

    @Test
    void a_failed_turn_leaves_nothing_for_the_next_one() {
        when(downloads.getDownloadOptions(any(), anyString())).thenReturn(List.of(BotResponse.text("stale card")));
        model.callTool("downloadMusic", "{\"artist\":\"Burial\",\"album\":\"Untrue\"}");
        model.fail(new IllegalStateException("model overloaded"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.chat(CTX, "скачай burial untrue"))
                .hasMessageContaining("model overloaded");

        model.reply("привіт");

        assertThat(sut.chat(CTX, "привіт")).containsExactly(BotResponse.aiText("привіт"));
    }

    private MainAgent agent() {
        DiscoveryTools discovery = new DiscoveryTools(aggregatedSearch, searchContext, extractor,
                mock(ReleaseRecommender.class), mock(MusicBrainzClient.class), mock(RelatedArtistsCollector.class),
                mock(MusicResearcher.class), mock(com.sashkomusic.libraryagent.domain.service.LibrarySearchService.class));
        return AiServices.builder(MainAgent.class)
                .chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(32))
                .tools(new MainAgentTools(library, downloads), discovery)
                .build();
    }

    /** Answers each request with the next scripted step and keeps every request it was sent. */
    private static final class ScriptedModel implements ChatModel {

        private final Deque<Object> steps = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        void callTool(String name, String arguments) {
            steps.add(AiMessage.from(ToolExecutionRequest.builder().id("call-" + steps.size())
                    .name(name).arguments(arguments).build()));
        }

        void reply(String text) {
            steps.add(AiMessage.from(text));
        }

        void fail(RuntimeException error) {
            steps.add(error);
        }

        String lastToolResult() {
            return requests.getLast().messages().stream()
                    .filter(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage)
                    .map(m -> ((dev.langchain4j.data.message.ToolExecutionResultMessage) m).text())
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            Object step = steps.removeFirst();
            if (step instanceof RuntimeException error) throw error;
            return ChatResponse.builder().aiMessage((AiMessage) step).build();
        }
    }
}
