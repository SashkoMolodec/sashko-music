package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.events.ReleasePlacedEvent;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Announces that a freshly processed release has its final place. Audio analysis and the Apple Music
 * sync read files by path, so they wait for this instead of racing the user's sub-library choice.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReleasePlacementService {

    private final ReleaseRepository releaseRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Only the first choice announces: later moves of an already placed release do nothing here. */
    public void confirm(String conversationId, Long releaseId) {
        if (releaseRepository.clearPlacementPending(releaseId) == 0) {
            log.debug("Release {} is already placed — nothing to announce", releaseId);
            return;
        }
        releaseRepository.findById(releaseId).ifPresent(release -> {
            log.info("Release {} placed in {} at {}", releaseId, release.getSublibrary(), release.getDirectoryPath());
            eventPublisher.publishEvent(new ReleasePlacedEvent(
                    conversationId, releaseId, release.getTitle(), release.getDirectoryPath()));
        });
    }
}
