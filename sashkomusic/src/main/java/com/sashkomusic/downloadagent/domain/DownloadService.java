package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.downloadagent.domain.model.DownloadBatch;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;
import com.sashkomusic.downloadagent.messaging.producer.dto.DownloadErrorDto;
import com.sashkomusic.downloadagent.messaging.producer.DownloadErrorProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class DownloadService {

    private final Map<DownloadEngine, MusicSourcePort> musicSources;
    private final DownloadErrorProducer errorProducer;
    private final DownloadContext downloadContext;

    public void download(DownloadFilesTaskDto task) {
        try {
            DownloadOption option = task.downloadOption();

            List<String> filenames = option.files().stream()
                    .map(DownloadOption.FileItem::filename)
                    .toList();

            downloadContext.registerBatch(task.conversationId(), task.releaseId(), filenames, option.source());
            MusicSourcePort client = musicSources.get(option.source());
            log.info("Using {} client for download", option.source());

            String downloadId = client.initiateDownload(option, task.releaseId());
            log.info("Download initiated: downloadId={}, source={}, releaseId={}, files={}",
                    downloadId, option.source(), task.releaseId(), filenames.size());

            String downloadPath = client.getDownloadPath(option);
            client.handleDownloadCompletion(task.conversationId(), task.releaseId(), option, downloadPath);

        } catch (MusicDownloadException e) {
            log.error("Download failed for conversationId={}: {}", task.conversationId(), e.getMessage());
            errorProducer.sendError(DownloadErrorDto.of(task.conversationId(), e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during download for conversationId={}: {}", task.conversationId(), e.getMessage(), e);
            errorProducer.sendError(DownloadErrorDto.of(task.conversationId(), "шось не то, пупупу... " + e.getMessage()));
        }
    }

    public void cancelDownload(String conversationId, String releaseId) {
        log.info("Attempting to cancel download for conversationId={}, releaseId={}", conversationId, releaseId);

        DownloadBatch batch = downloadContext.findBatchByReleaseId(releaseId);

        if (batch == null) {
            log.warn("Cancel failed: no active download found for releaseId={}", releaseId);
            errorProducer.sendError(DownloadErrorDto.of(conversationId,
                    "завантаження вже завершилось або не знайдено 🤷"));
            return;
        }

        DownloadEngine source = batch.getSource();
        MusicSourcePort client = musicSources.get(source);

        if (client != null) {
            client.cancelDownload(releaseId);
        }

        downloadContext.removeBatchByReleaseId(releaseId);

        log.info("Successfully cancelled download for releaseId={}", releaseId);
        errorProducer.sendError(DownloadErrorDto.of(conversationId, "❌ **скасовано завантаження**"));
    }
}
