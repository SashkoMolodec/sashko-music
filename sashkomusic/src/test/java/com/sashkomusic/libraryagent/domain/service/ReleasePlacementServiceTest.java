package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.events.ReleasePlacedEvent;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleasePlacementServiceTest {

    private ReleaseRepository releaseRepository;
    private ApplicationEventPublisher publisher;
    private ReleasePlacementService service;

    @BeforeEach
    void setUp() {
        releaseRepository = mock(ReleaseRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new ReleasePlacementService(releaseRepository, publisher);
    }

    @Test
    void the_first_choice_announces_the_release_at_its_final_path() {
        Release release = new Release();
        release.setId(173L);
        release.setTitle("Естрадний ансамбль Арніка");
        release.setSublibrary("vault");
        release.setDirectoryPath("/lib/vault/арніка/естрадний ансамбль арніка (1974) [flac]");
        when(releaseRepository.clearPlacementPending(173L)).thenReturn(1);
        when(releaseRepository.findById(173L)).thenReturn(Optional.of(release));

        service.confirm("-100:5", 173L);

        ArgumentCaptor<ReleasePlacedEvent> event = ArgumentCaptor.forClass(ReleasePlacedEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().conversationId()).isEqualTo("-100:5");
        assertThat(event.getValue().releaseId()).isEqualTo(173L);
        assertThat(event.getValue().directoryPath()).isEqualTo("/lib/vault/арніка/естрадний ансамбль арніка (1974) [flac]");
    }

    @Test
    void a_later_move_or_a_second_click_announces_nothing() {
        when(releaseRepository.clearPlacementPending(173L)).thenReturn(0);

        service.confirm("-100:5", 173L);

        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
