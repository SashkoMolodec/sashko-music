package com.sashkomusic.downloadagent.messaging.consumer;

import com.sashkomusic.downloadagent.domain.AcquisitionService;
import com.sashkomusic.events.FilesSearchTaskEvent;
import com.sashkomusic.shared.task.SearchFilesTask;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchTaskListener {

    private final AcquisitionService acquisitionService;

    @EventListener
    @Async("asyncExecutor")
    public void handleSearchTask(FilesSearchTaskEvent event) {
        SearchFilesTask task = event.payload();
        acquisitionService.search(task);
    }
}
