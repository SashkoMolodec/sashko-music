package com.sashkomusic.libraryagent.messaging.consumer;

import com.sashkomusic.events.MoveReleaseCompleteEvent;
import com.sashkomusic.events.MoveReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.service.ReleasePlacementService;
import com.sashkomusic.libraryagent.domain.service.ReleaseRelocationService;
import com.sashkomusic.libraryagent.domain.service.ReleaseRelocationService.RelocationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoveReleaseListenerTest {

    private static final MoveReleaseTaskEvent TASK = new MoveReleaseTaskEvent("-100:5", 173L, "vault");

    private ReleaseRelocationService relocationService;
    private ReleasePlacementService placementService;
    private ApplicationEventPublisher publisher;
    private MoveReleaseListener listener;

    @BeforeEach
    void setUp() {
        relocationService = mock(ReleaseRelocationService.class);
        placementService = mock(ReleasePlacementService.class);
        publisher = mock(ApplicationEventPublisher.class);
        listener = new MoveReleaseListener(relocationService, placementService, publisher);
    }

    @Test
    void a_successful_move_confirms_the_placement() {
        when(relocationService.move(173L, "vault"))
                .thenReturn(new RelocationResult(true, null, "t", "a", "/old", "/new", "ok"));

        listener.handle(TASK);

        verify(placementService).confirm("-100:5", 173L);
    }

    @Test
    void a_failed_move_leaves_the_release_waiting() {
        when(relocationService.move(173L, "vault"))
                .thenReturn(new RelocationResult(false, null, "t", "a", "/old", "/new", "target already exists"));

        listener.handle(TASK);

        verify(placementService, never()).confirm(anyString(), anyLong());
    }

    @Test
    void a_failure_confirming_the_placement_does_not_turn_the_move_into_a_failed_one() {
        when(relocationService.move(173L, "vault"))
                .thenReturn(new RelocationResult(true, null, "t", "a", "/old", "/new", "ok"));
        doThrow(new IllegalStateException("db down")).when(placementService).confirm(anyString(), anyLong());

        listener.handle(TASK);

        ArgumentCaptor<MoveReleaseCompleteEvent> events = ArgumentCaptor.forClass(MoveReleaseCompleteEvent.class);
        verify(publisher).publishEvent(events.capture());
        assertThat(events.getAllValues()).singleElement().satisfies(e -> assertThat(e.success()).isTrue());
    }
}
