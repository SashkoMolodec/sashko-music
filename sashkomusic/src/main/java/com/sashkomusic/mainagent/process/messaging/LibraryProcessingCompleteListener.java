package com.sashkomusic.mainagent.process.messaging;

import com.sashkomusic.events.LibraryProcessingCompleteEvent;
import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.model.ProcessedFile;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.messaging.producer.dto.LibraryProcessingCompleteDto;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.bot.TelegramChatBot;
import com.sashkomusic.mainagent.library.LastReleaseContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class LibraryProcessingCompleteListener {

    private final TelegramChatBot chatBot;
    private final ReleaseRepository releaseRepository;
    private final LibraryConfig libraryConfig;
    private final LastReleaseContextHolder lastReleaseContextHolder;

    @EventListener
    @Async
    @Transactional(readOnly = true)
    public void handleLibraryProcessingComplete(LibraryProcessingCompleteEvent event) {
        LibraryProcessingCompleteDto result = event.payload();
        log.info("Received library processing result: conversationId={}, success={}, processedFiles={}",
                result.conversationId(), result.success(), result.processedFiles().size());

        String text = buildResultText(result);

        if (!result.success()) {
            chatBot.sendMessage(ConversationContext.from(result.conversationId()), text);
            return;
        }

        Optional<Release> releaseOpt = releaseRepository.findByDirectoryPath(result.directoryPath());
        if (releaseOpt.isPresent()) {
            Release release = releaseOpt.get();
            String artist = release.getArtists().isEmpty()
                    ? null
                    : release.getArtists().iterator().next().getName();
            lastReleaseContextHolder.set(result.conversationId(), release.getId(), release.getTitle(), artist);

            Map<String, String> buttons = buildSublibButtons(release);
            if (buttons.isEmpty()) {
                chatBot.sendMessage(ConversationContext.from(result.conversationId()), text);
            } else {
                String prompt = text + "\n\n📦 куди покласти?";
                BotResponse response = BotResponse.withButtons(prompt, buttons);
                chatBot.sendResponse(ConversationContext.from(result.conversationId()), response);
            }
        } else {
            log.warn("Could not resolve release entity for directoryPath={}", result.directoryPath());
            chatBot.sendMessage(ConversationContext.from(result.conversationId()), text);
        }
    }

    private Map<String, String> buildSublibButtons(Release release) {
        Map<String, String> buttons = new LinkedHashMap<>();
        String current = release.getSublibrary();
        for (String sublib : libraryConfig.getSublibraries()) {
            String label = sublib.equals(current) ? "✅ " + sublib : icon(sublib) + " " + sublib;
            buttons.put(label, "LIB_ASSIGN:" + release.getId() + ":" + sublib);
        }
        return buttons;
    }

    private String icon(String sublib) {
        return switch (sublib) {
            case "working" -> "📁";
            case "vault" -> "💎";
            default -> "📦";
        };
    }

    private String buildResultText(LibraryProcessingCompleteDto result) {
        String[] artistAndRelease = extractArtistAndRelease(result.directoryPath());
        String artist = artistAndRelease[0];
        String releaseFolder = artistAndRelease[1];

        if (result.success()) {
            String tracks = formatProcessedFiles(result.processedFiles());
            return "✅ додано в лібку!\n\n📁 _%s_ → _%s_\n%s".formatted(artist, releaseFolder, tracks);
        }
        return String.format("""
                        ❌ помилка обробки релізу
                        📁 _%s_ → _%s_
                        %s
                        %s
                        """,
                artist, releaseFolder, result.message(),
                result.errors().isEmpty() ? "" : "помилки:\n" + String.join("\n", result.errors())
        );
    }

    private String formatProcessedFiles(List<ProcessedFile> files) {
        if (files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        files.stream()
                .sorted(java.util.Comparator.comparing(ProcessedFile::trackNumber))
                .forEach(f -> sb.append("_%02d. %s_\n".formatted(f.trackNumber(), f.trackTitle().toLowerCase())));
        return sb.toString();
    }

    private String[] extractArtistAndRelease(String path) {
        if (path == null || path.isEmpty()) return new String[]{"unknown", ""};
        String cleanPath = path.endsWith("/") || path.endsWith("\\") ? path.substring(0, path.length() - 1) : path;
        int lastSlash = Math.max(cleanPath.lastIndexOf('\\'), cleanPath.lastIndexOf('/'));
        if (lastSlash < 0) return new String[]{"unknown", cleanPath};
        String releaseFolder = cleanPath.substring(lastSlash + 1);
        String parentPath = cleanPath.substring(0, lastSlash);
        int secondLastSlash = Math.max(parentPath.lastIndexOf('\\'), parentPath.lastIndexOf('/'));
        String artistFolder = secondLastSlash >= 0 ? parentPath.substring(secondLastSlash + 1) : parentPath;
        return new String[]{artistFolder, releaseFolder};
    }
}
