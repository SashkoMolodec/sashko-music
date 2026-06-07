package com.sashkomusic.agents.library;

import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.model.LibrarySearchResult;
import com.sashkomusic.libraryagent.domain.service.LibrarySearchService;
import com.sashkomusic.libraryagent.domain.smartlist.SmartlistService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.LastReleaseContextHolder;
import com.sashkomusic.mainagent.library.RemoveReleaseFlowService;
import com.sashkomusic.mainagent.library.SmartlistCreationFlowService;
import com.sashkomusic.mainagent.library.messaging.MoveReleaseTaskProducer;
import com.sashkomusic.mainagent.process.ProcessFolderFlowService;
import com.sashkomusic.mainagent.process.ReprocessReleasesFlowService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryAgentTools {

    private static final Set<String> THIS_RELEASE_MARKERS = Set.of(
            "", "this", "this release", "оцей", "цей", "цього", "цей реліз", "оцей реліз",
            "щойно", "last", "the last one", "поточний");

    private final LibrarySearchService librarySearchService;
    private final LibraryConfig libraryConfig;
    private final LastReleaseContextHolder lastReleaseContextHolder;
    private final MoveReleaseTaskProducer moveTaskProducer;
    private final RemoveReleaseFlowService removeReleaseFlowService;
    private final ChatResponseAccumulator accumulator;
    private final ProcessFolderFlowService processFolderFlowService;
    private final ReprocessReleasesFlowService reprocessReleasesFlowService;
    private final SmartlistCreationFlowService smartlistCreationFlowService;
    private final SmartlistService smartlistService;

    // ───────────────── catalog ops ─────────────────

    @Tool("Search the user's own processed music library (full-text). Use for 'чи є в мене', 'in my library', or any 'do I have X' question.")
    public String searchOwnLibrary(
            @P("search query — artist, album, genre tag, or combination") String query,
            @ToolMemoryId String conversationId) {
        List<LibrarySearchResult> results = librarySearchService.search(query, 5);
        if (results.isEmpty()) {
            return "нічого не знайдено у твоїй бібліотеці за запитом: " + query;
        }
        LibrarySearchResult top = results.get(0);
        lastReleaseContextHolder.set(mainConversationId(conversationId), top.releaseId(), top.title(), top.artists());

        var sb = new StringBuilder("Знайдено у твоїй бібліотеці (").append(results.size()).append("):\n");
        for (LibrarySearchResult r : results) {
            sb.append("- ");
            if (r.artists() != null && !r.artists().isBlank()) sb.append(r.artists()).append(" — ");
            sb.append(r.title());
            if (r.year() != null) sb.append(" (").append(r.year()).append(")");
            if (r.tags() != null && !r.tags().isBlank()) sb.append(" [").append(r.tags()).append("]");
            sb.append(", ").append(r.trackCount()).append(" треків\n");
        }
        return sb.toString().strip();
    }

    @Tool("Move a release between physical sub-libraries (working/vault/...). Files are moved on disk and DB updated.")
    public String moveReleaseToSublibrary(
            @P("release reference: full text query, or 'this' / 'оцей' to use the last-referenced release") String releaseQuery,
            @P("target sublibrary name (must be one of those from listSublibraries)") String sublibrary,
            @ToolMemoryId String conversationId) {
        if (sublibrary == null || sublibrary.isBlank()) {
            return "не вказана цільова бібліотека";
        }
        if (!libraryConfig.getSublibraries().contains(sublibrary)) {
            return "невідома бібліотека '" + sublibrary + "', доступні: " + libraryConfig.getSublibraries();
        }
        String mainId = mainConversationId(conversationId);
        ReleaseRef ref = resolveRelease(releaseQuery, mainId);
        if (ref == null) {
            return "не знайшов реліз — уточни назву";
        }
        moveTaskProducer.send(mainId, ref.id(), sublibrary);
        return "переношу " + ref.label() + " у " + sublibrary;
    }

    @Tool("Send a release to trash (soft delete: files moved to /trash, DB row removed). Always shows confirmation buttons.")
    public String trashRelease(
            @P("release reference: full text query, or 'this' / 'оцей' for last-referenced") String releaseQuery,
            @ToolMemoryId String conversationId) {
        String mainId = mainConversationId(conversationId);
        ReleaseRef ref = resolveRelease(releaseQuery, mainId);
        List<BotResponse> card = ref == null
                ? removeReleaseFlowService.presentConfirmationByQuery(releaseQuery)
                : removeReleaseFlowService.presentConfirmationByReleaseId(ref.id());
        accumulator.pushAll(mainId, card);
        return "показав картку підтвердження видалення";
    }

    @Tool("List available sub-library names (e.g. working, vault). Use only when the user asks about valid targets.")
    public String listSublibraries() {
        return String.join(", ", libraryConfig.getSublibraries());
    }

    @Tool("""
            Process a downloaded folder into the user's library: scan files, identify the release, search metadata,
            and show metadata-source options for the user to confirm. Triggers a multi-turn dialogue.
            Use for: "обробити папку X", "process Y", "опрацюй цей даунлоад", "запусти process на ...".
            folderHint = folder name or path under downloads, exactly as the user gave it (or empty for the most recent).
            """)
    public String processFolder(
            @P("folder name or path (relative to downloads root), or empty to use the most recent download") String folderHint,
            @ToolMemoryId String conversationId) {
        String hint = folderHint == null ? "" : folderHint.trim();
        String mainId = mainConversationId(conversationId);
        List<BotResponse> responses = processFolderFlowService.process(
                ConversationContext.from(mainId), hint);
        accumulator.pushAll(mainId, responses);
        return "запустив обробку папки '" + (hint.isBlank() ? "(last download)" : hint) + "'";
    }

    @Tool("""
            Reprocess an already-organized release in the user's library — re-fetch metadata, re-tag files,
            re-index search. Or reprocess ALL releases at once.
            Triggers: "переобробити X", "репроцесни Y", "reprocess Y", "запусти reprocess all", "перетегни все".
            target = the path or release reference; pass 'all' for the whole library.
            skipRetag = true → don't fetch fresh metadata, only re-tag from on-disk metadata file.
            force = true → reprocess even if the release is already on the current processing version.
            """)
    public String reprocessRelease(
            @P("path or release reference, or 'all' for everything") String target,
            @P("true to skip metadata refetch, false otherwise") boolean skipRetag,
            @P("true to force reprocess even if up-to-date, false otherwise") boolean force,
            @ToolMemoryId String conversationId) {
        if (target == null || target.isBlank()) {
            return "не вказано що реобробляти — назви шлях або 'all'";
        }
        StringBuilder cmd = new StringBuilder("/reprocess");
        if (skipRetag) cmd.append(" --skip-retag");
        if (force) cmd.append(" --force");
        cmd.append(' ').append(target.trim());
        ReprocessReleasesFlowService.ReprocessResult result =
                reprocessReleasesFlowService.handle(ConversationContext.from(mainConversationId(conversationId)), cmd.toString());
        return result.message();
    }

    // ───────────────── smartlists ─────────────────

    @Tool("""
            Start creating a smart playlist (dynamic M3U updated when tracks change).
            Use for: "створи смартлист X — house 2024 з рейтингом 4+", "make smartlist <name> with <rules>".
            Triggers a confirmation card with a 5-track preview and ✅/❌ buttons.
            Supported rules: year/comment/label/genre 'contains' a substring, rating range 1..5,
            or 'is' exact match (with null meaning the tag is absent — e.g. "rating is null" = unrated tracks).
            Conditions are joined with AND.
            """)
    public String createSmartlist(
            @P("smartlist name (short, unique)") String name,
            @P("natural-language description of the membership rules") String naturalDescription,
            @ToolMemoryId String conversationId) {
        String mainId = mainConversationId(conversationId);
        accumulator.pushAll(mainId,
                smartlistCreationFlowService.startCreate(ConversationContext.from(mainId), name, naturalDescription));
        return "показав картку підтвердження смартлиста '" + name + "'";
    }

    @Tool("List all smart playlists with track counts and DSL rule. Use for: 'які в мене смартлисти', 'list smartlists'.")
    public String listSmartlists() {
        List<SmartlistService.SmartlistSummary> all = smartlistService.list();
        if (all.isEmpty()) return "ще немає жодного смартлиста";
        StringBuilder sb = new StringBuilder("Смартлисти (").append(all.size()).append("):\n");
        for (var s : all) {
            sb.append("• ").append(s.name()).append(" — ").append(s.trackCount()).append(" треків [")
                    .append(s.dslDescription()).append("]\n");
        }
        return sb.toString().strip();
    }

    @Tool("Rename a smart playlist. The M3U file is renamed on disk too.")
    public String renameSmartlist(
            @P("current smartlist name") String oldName,
            @P("new smartlist name") String newName) {
        try {
            var s = smartlistService.rename(oldName, newName);
            return "✅ перейменовано на '" + s.name() + "' (" + s.trackCount() + " треків)";
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        }
    }

    @Tool("Delete a smart playlist by name. The M3U file is also removed.")
    public String deleteSmartlist(@P("smartlist name") String name) {
        return smartlistService.delete(name)
                ? "✅ видалено смартлист '" + name + "'"
                : "❌ смартлист '" + name + "' не знайдено";
    }

    // ───────────────── helpers ─────────────────

    private record ReleaseRef(Long id, String label) {}

    private static String mainConversationId(String memoryId) {
        return memoryId != null && memoryId.endsWith(":lib")
                ? memoryId.substring(0, memoryId.length() - 4)
                : memoryId;
    }

    protected ReleaseRef resolveRelease(String releaseQuery, String conversationId) {
        String q = releaseQuery == null ? "" : releaseQuery.trim().toLowerCase();
        if (THIS_RELEASE_MARKERS.contains(q)) {
            Optional<LastReleaseContextHolder.LastReleaseContext> last =
                    lastReleaseContextHolder.get(conversationId);
            if (last.isPresent()) {
                var ctx = last.get();
                String label = ctx.artist() != null
                        ? ctx.artist() + " — " + ctx.title()
                        : ctx.title();
                return new ReleaseRef(ctx.releaseId(), label);
            }
            return null;
        }
        List<LibrarySearchResult> results = librarySearchService.search(releaseQuery, 1);
        if (results.isEmpty()) return null;
        LibrarySearchResult top = results.get(0);
        String label = (top.artists() != null && !top.artists().isBlank())
                ? top.artists() + " — " + top.title()
                : top.title();
        lastReleaseContextHolder.set(conversationId, top.releaseId(), top.title(), top.artists());
        return new ReleaseRef(top.releaseId(), label);
    }
}
