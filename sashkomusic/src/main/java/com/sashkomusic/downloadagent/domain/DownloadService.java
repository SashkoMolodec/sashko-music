package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.domain.exception.MusicDownloadException;
import com.sashkomusic.downloadagent.domain.model.DownloadBatch;
import com.sashkomusic.shared.download.DownloadEngine;
import com.sashkomusic.shared.download.DownloadOption;
import com.sashkomusic.shared.task.DownloadFilesTask;
import com.sashkomusic.events.DownloadErrorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class DownloadService {

    private final Map<DownloadEngine, MusicSourcePort> musicSources;
    private final ApplicationEventPublisher eventPublisher;
    private final DownloadContext downloadContext;

    public void download(DownloadFilesTask task) {
        try {
            DownloadOption option = task.downloadOption();

            List<String> filenames = option.files().stream()
                    .map(DownloadOption.FileItem::filename)
                    .toList();

            downloadContext.registerBatch(task.conversationId(), task.releaseId(), filenames, option.source());
            MusicSourcePort client = musicSources.get(option.source());
            log.info("Using {} client for download", option.source());

            String downloadId = client.initiateDownload(option, task.releaseId(), task.conversationId());
            log.info("Download initiated: downloadId={}, source={}, releaseId={}, files={}",
                    downloadId, option.source(), task.releaseId(), filenames.size());

            String downloadPath = client.getDownloadPath(option);
            client.handleDownloadCompletion(task.conversationId(), task.releaseId(), option, downloadPath);

        } catch (MusicDownloadException e) {
            log.error("Download failed for conversationId={}: {}", task.conversationId(), e.getMessage());
            eventPublisher.publishEvent(new DownloadErrorEvent(task.conversationId(), e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during download for conversationId={}: {}", task.conversationId(), e.getMessage(), e);
            eventPublisher.publishEvent(new DownloadErrorEvent(task.conversationId(), "шось не то, пупупу... " + e.getMessage()));
        }
    }

}
