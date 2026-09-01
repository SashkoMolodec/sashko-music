package com.sashkomusic.mainagent.download;

import com.sashkomusic.downloadagent.domain.SoulseekDirectoryService;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.messaging.DownloadTaskProducer;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SoulseekDirectoryPreviewFlowService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "bmp", "gif");

    private final SoulseekDirectoryService directoryService;
    private final SoulseekDirectoryConfirmContextHolder confirmHolder;
    private final DownloadContextHolder downloadContextHolder;
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

        String text = "📁 повна директорія:\n\n" + DownloadOptionsCardFormatter.formatSingle(expanded);

        return List.of(BotResponse.withMultiRowButtons(text, List.of(
                List.of(
                        new BotResponse.ButtonDto("✅", "SLSK_DIR_OK"),
                        new BotResponse.ButtonDto("🔢", "SLSK_DIR_SEL"),
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
        return finalizeDownload(ctx, confirm.releaseId(), confirm.expandedOption());
    }

    public List<BotResponse> handleCancel(ConversationContext ctx) {
        confirmHolder.clear(ctx.conversationId());
        return List.of(BotResponse.text("❌ скасовано"));
    }

    public List<BotResponse> promptSelection(ConversationContext ctx) {
        var pending = confirmHolder.get(ctx.conversationId());
        if (pending.isEmpty()) {
            return List.of(BotResponse.text("😔 підтвердження вже протухло — знайди реліз ще раз"));
        }
        confirmHolder.markSelecting(ctx.conversationId());
        return List.of(BotResponse.text("🤔 введи номери треків через кому (напр. 1,2,5)"));
    }

    public boolean isSelecting(ConversationContext ctx) {
        return confirmHolder.get(ctx.conversationId())
                .map(SoulseekDirectoryConfirmContextHolder.PendingConfirm::selecting)
                .orElse(false);
    }

    public List<BotResponse> handleSelection(ConversationContext ctx, String input) {
        var pending = confirmHolder.get(ctx.conversationId());
        if (pending.isEmpty()) {
            return List.of(BotResponse.text("😔 підтвердження вже протухло — знайди реліз ще раз"));
        }
        var confirm = pending.get();
        List<DownloadOption.FileItem> allFiles = confirm.expandedOption().files();

        List<Integer> numbers;
        try {
            numbers = Arrays.stream(input.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException e) {
            return List.of(BotResponse.text("не зрозумів номери — введи через кому, напр. 1,2,5"));
        }

        if (numbers.isEmpty()) {
            return List.of(BotResponse.text("вкажи хоч один номер треку"));
        }
        for (int n : numbers) {
            if (n < 1 || n > allFiles.size()) {
                return List.of(BotResponse.text("номер %d поза межами (усього %d файлів)".formatted(n, allFiles.size())));
            }
        }

        Set<DownloadOption.FileItem> selected = new LinkedHashSet<>();
        for (int n : numbers) {
            selected.add(allFiles.get(n - 1));
        }
        allFiles.stream().filter(f -> isImage(f.displayName())).forEach(selected::add);

        int totalSizeMB = (int) (selected.stream().mapToLong(DownloadOption.FileItem::size).sum() / (1024L * 1024L));
        DownloadOption filtered = new DownloadOption(
                confirm.expandedOption().id(),
                confirm.expandedOption().source(),
                confirm.expandedOption().displayName(),
                totalSizeMB,
                List.copyOf(selected),
                confirm.expandedOption().technicalMetadata()
        );

        return finalizeDownload(ctx, confirm.releaseId(), filtered);
    }

    private List<BotResponse> finalizeDownload(ConversationContext ctx, String releaseId, DownloadOption option) {
        confirmHolder.clear(ctx.conversationId());
        downloadContextHolder.clearSession(ctx.conversationId());

        downloadTaskProducer.send(DownloadFilesTaskDto.of(ctx.conversationId(), releaseId, option));
        log.info("Confirmed Soulseek directory download: releaseId={}, files={}", releaseId, option.files().size());

        return List.of(BotResponse.text("✅ *ок, качаю:*\n%s\n📦 %d файлів, %d MB".formatted(
                option.displayName(),
                option.files().size(),
                option.totalSize()
        )));
    }

    private boolean isImage(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot >= filename.length() - 1) return false;
        return IMAGE_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase());
    }
}
