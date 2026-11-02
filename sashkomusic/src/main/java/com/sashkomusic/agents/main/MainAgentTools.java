package com.sashkomusic.agents.main;

import com.sashkomusic.agents.library.LibraryAgentService;
import com.sashkomusic.agents.turn.AgentTurn;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.MusicDownloadFlowService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** MainAgent's library and download tools; discovery tools live in {@code DiscoveryTools}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MainAgentTools {

    private final LibraryAgentService libraryAgent;
    private final MusicDownloadFlowService musicDownloadFlowService;

    @Tool("Delegate any operation on the user's own music library to LibraryAgent — searching it, moving releases between sub-libraries, trashing, processing downloads, smartlists, info and tracklists of owned releases, and what the player is playing right now.")
    public String manageLibrary(
            @P("the user's natural-language library command, verbatim") String command,
            InvocationParameters parameters) {
        AgentTurn turn = AgentTurn.of(parameters);
        turn.progress("📚 бібліотека...");
        return libraryAgent.handle(turn, command);
    }

    @Tool("Start downloading a release the user is asking for by artist/album text (e.g. 'скачай Roberto Carlos 1976'). Do NOT use for button-driven downloads — those go through CallbackDispatcher.")
    public String downloadMusic(
            @P("artist name, or empty if unknown") String artist,
            @P("album / release title, or empty if unknown") String album,
            InvocationParameters parameters) {
        AgentTurn turn = AgentTurn.of(parameters);
        String query = Stream.of(artist, album)
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        if (query.isBlank()) {
            return "нема що качати — порожній запит";
        }
        turn.progress("⬇️ качаю...");
        List<BotResponse> responses;
        try {
            responses = musicDownloadFlowService.getDownloadOptions(turn.context(), query);
        } catch (Exception ex) {
            log.error("Download flow failure for '{}': {}", query, ex.getMessage(), ex);
            return "не вдалось почати: " + ex.getMessage();
        }
        turn.show(responses);
        String summary = responses.stream()
                .map(BotResponse::text)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining(" | "));
        return summary.isBlank() ? "почав качати" : summary;
    }
}
