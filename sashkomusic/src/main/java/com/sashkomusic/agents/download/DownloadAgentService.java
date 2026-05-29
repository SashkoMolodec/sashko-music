package com.sashkomusic.agents.download;

import com.sashkomusic.agents.contract.DownloadRequest;
import com.sashkomusic.agents.contract.DownloadResult;
import com.sashkomusic.agents.bridge.ChatResponseAccumulator;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.download.MusicDownloadFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadAgentService {

    private final MusicDownloadFlowService musicDownloadFlowService;
    private final ChatResponseAccumulator accumulator;

    public DownloadResult handle(DownloadRequest request) {
        log.info("Download agent handling: chatId={}, releaseId={}, artist={}, album={}",
                request.chatId(), request.releaseId(), request.artist(), request.album());

        List<BotResponse> responses;
        try {
            if (request.releaseId() != null && !request.releaseId().isBlank()) {
                responses = musicDownloadFlowService.handleDownload(request.chatId(), "DL:" + request.releaseId());
            } else {
                String query = buildQuery(request);
                if (query.isBlank()) {
                    return DownloadResult.failed("нема що качати — порожній запит");
                }
                responses = musicDownloadFlowService.getDownloadOptions(request.chatId(), query);
            }
        } catch (Exception ex) {
            log.error("Download flow failure: {}", ex.getMessage(), ex);
            return DownloadResult.failed("не вдалось почати: " + ex.getMessage());
        }

        responses.forEach(r -> accumulator.push(request.chatId(), r));

        String summary = responses.stream()
                .map(BotResponse::text)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining(" | "));
        return DownloadResult.started(summary.isBlank() ? "почав качати" : summary);
    }

    private String buildQuery(DownloadRequest request) {
        String artist = request.artist() == null ? "" : request.artist();
        String album = request.album() == null ? "" : request.album();
        return (artist + " " + album).trim();
    }
}
