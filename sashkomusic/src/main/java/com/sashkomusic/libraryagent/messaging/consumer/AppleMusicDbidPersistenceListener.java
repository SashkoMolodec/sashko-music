package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.AppleMusicSyncCompleteEvent;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class AppleMusicDbidPersistenceListener {

    private final TrackRepository trackRepository;

    @EventListener
    @Async
    @Transactional
    public void handle(AppleMusicSyncCompleteEvent event) {
        for (AppleMusicSyncCompleteEvent.TrackDbid mapping : event.tracks()) {
            trackRepository.findByLocalPath(mapping.filePath()).ifPresentOrElse(track -> {
                track.setAppleMusicDbid(mapping.dbid());
                trackRepository.save(track);
                log.info("Persisted Apple Music dbid={} for track id={}", mapping.dbid(), track.getId());
            }, () -> log.warn("No track found for Apple Music sync path: {}", mapping.filePath()));
        }
    }
}
