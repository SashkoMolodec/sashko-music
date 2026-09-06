package com.sashkomusic.mainagent.bot.photo;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.download.DirectSoulseekSearchFlowService;
import com.sashkomusic.mainagent.search.MetadataUrlFetcher;
import com.sashkomusic.mainagent.search.ReleaseSearchFlowService;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import dev.langchain4j.data.message.ImageContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoSearchFlowService {

    private static final String DIRECT_DOWNLOAD_KEYWORD = "копай";
    private static final String UNKNOWN_MARKER = "UNKNOWN";

    private final TelegramClient telegramClient;
    private final PhotoReleaseTextExtractor photoReleaseTextExtractor;
    private final ReleaseSearchFlowService releaseSearchFlowService;
    private final DirectSoulseekSearchFlowService directSoulseekSearchFlowService;
    private final GoogleVisionReleaseIdentifier visionReleaseIdentifier;
    private final MetadataUrlFetcher metadataUrlFetcher;

    public List<BotResponse> handlePhoto(ConversationContext ctx, List<PhotoSize> photoSizes, String caption) {
        byte[] imageBytes;
        try {
            imageBytes = downloadLargestPhoto(photoSizes);
        } catch (Exception e) {
            log.warn("Failed to download photo for [{}]: {}", ctx.conversationId(), e.getMessage());
            return List.of(BotResponse.text("😔 не вдалось завантажити фото, спробуй ще раз."));
        }

        Optional<ReleaseMetadata> visionMatch = visionReleaseIdentifier.identifyDiscogsUrl(imageBytes)
                .flatMap(metadataUrlFetcher::fetch);
        if (visionMatch.isPresent()) {
            return respondWithRelease(ctx, visionMatch.get(), caption);
        }

        String recognized;
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            recognized = photoReleaseTextExtractor.extract(
                    "Extract release info from this photo.", ImageContent.from(base64, "image/jpeg"));
        } catch (Exception e) {
            log.error("Vision extraction failed for [{}]: {}", ctx.conversationId(), e.getMessage(), e);
            return List.of(BotResponse.text("😔 не вдалось розпізнати фото. спробуй ще раз або напиши текстом."));
        }

        if (recognized == null || recognized.isBlank() || recognized.trim().equalsIgnoreCase(UNKNOWN_MARKER)) {
            return List.of(BotResponse.text("😔 не розпізнав нічого на фото. сфоткай чіткіше або напиши текстом."));
        }
        String query = recognized.trim();

        List<BotResponse> responses = new ArrayList<>();
        responses.add(BotResponse.text("🔎 розпізнав з фото: " + query));
        responses.addAll(isDirectDownloadCaption(caption)
                ? directSoulseekSearchFlowService.search(ctx, query)
                : releaseSearchFlowService.searchDefault(ctx, query));
        return responses;
    }

    private List<BotResponse> respondWithRelease(ConversationContext ctx, ReleaseMetadata release, String caption) {
        String label = release.artist() + " - " + release.title();
        List<BotResponse> responses = new ArrayList<>();
        responses.add(BotResponse.text("🔎 знайшов по фото: " + label));
        responses.addAll(isDirectDownloadCaption(caption)
                ? directSoulseekSearchFlowService.search(ctx, label)
                : releaseSearchFlowService.showResolvedRelease(ctx, release, "photo"));
        return responses;
    }

    static boolean isDirectDownloadCaption(String caption) {
        return caption != null && caption.toLowerCase().contains(DIRECT_DOWNLOAD_KEYWORD);
    }

    private byte[] downloadLargestPhoto(List<PhotoSize> photoSizes) throws Exception {
        PhotoSize largest = photoSizes.stream()
                .max(Comparator.comparingInt(p -> p.getFileSize() != null ? p.getFileSize() : 0))
                .orElseThrow(() -> new IllegalArgumentException("no photo sizes present"));
        var file = telegramClient.execute(GetFile.builder().fileId(largest.getFileId()).build());
        try (InputStream in = telegramClient.downloadFileAsStream(file)) {
            return in.readAllBytes();
        }
    }
}
