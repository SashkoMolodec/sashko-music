package com.sashkomusic.mainagent.download;

import com.sashkomusic.downloadagent.domain.SoulseekDirectoryService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;
import com.sashkomusic.mainagent.download.messaging.DownloadTaskProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SoulseekDirectoryPreviewFlowService {

    private final SoulseekDirectoryService directoryService;
    private final SoulseekDirectoryConfirmContextHolder confirmHolder;
    private final DownloadTaskProducer downloadTaskProducer;

    public List<BotResponse> fetchAndShowPreview(ConversationContext ctx, String releaseId, DownloadOption original) {
        log.info("Fetching full directory for option: {}", original.displayName());

        DownloadOption expanded;
        try {
            expanded = directoryService.fetchExpandedOption(original);
        } catch (Exception e) {
            log.warn("Directory fetch failed for {}: {}", original.displayName(), e.getMessage());
            return List.of(BotResponse.text("❌ не вдалося завантажити директорію з slskd"));
        }

        confirmHolder.save(ctx.conversationId(), releaseId, expanded);

        String fileList = expanded.files().stream()
                .map(f -> f.displayName())
                .collect(Collectors.joining("\n"));

        long audioCount = expanded.files().stream()
                .filter(f -> isAudio(f.filename()))
                .count();

        String text = "📁 *%s*\n👤 %s\n\n%s\n\n_%d аудіо, %d MB_".formatted(
                escapeMarkdown(original.displayName()),
                escapeMarkdown(original.technicalMetadata().getOrDefault("username", "?")),
                fileList,
                audioCount,
                expanded.totalSize()
        );

        return List.of(BotResponse.withMultiRowButtons(text, List.of(
                List.of(
                        new BotResponse.ButtonDto("✅", "SLSK_DIR_OK"),
                        new BotResponse.ButtonDto("❌", "SLSK_DIR_NO")
                )
        )));
    }

    public List<BotResponse> handleConfirm(ConversationContext ctx) {
        var pending = confirmHolder.get(ctx.conversationId());
        if (pending.isEmpty()) {
            return List.of(BotResponse.text("😔 підтвердження вже протухло — знайди реліз ще раз"));
        }

        var confirm = pending.get();
        confirmHolder.clear(ctx.conversationId());

        downloadTaskProducer.send(DownloadFilesTaskDto.of(ctx.conversationId(), confirm.releaseId(), confirm.expandedOption()));
        log.info("Confirmed Soulseek directory download: releaseId={}, files={}", confirm.releaseId(), confirm.expandedOption().files().size());

        return List.of(BotResponse.text("✅ *ок, качаю:*\n%s\n📦 %d файлів, %d MB".formatted(
                confirm.expandedOption().displayName(),
                confirm.expandedOption().files().size(),
                confirm.expandedOption().totalSize()
        )));
    }

    public List<BotResponse> handleCancel(ConversationContext ctx) {
        confirmHolder.clear(ctx.conversationId());
        return List.of(BotResponse.text("❌ скасовано"));
    }

    private boolean isAudio(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav")
                || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg")
                || lower.endsWith(".alac") || lower.endsWith(".aiff");
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[");
    }
}
