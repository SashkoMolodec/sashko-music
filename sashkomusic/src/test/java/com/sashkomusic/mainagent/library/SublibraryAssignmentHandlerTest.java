package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.MoveReleaseTaskEvent;
import com.sashkomusic.events.PlaceReleaseTaskEvent;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.mainagent.bot.ConversationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SublibraryAssignmentHandlerTest {

    private static final ConversationContext CTX = ConversationContext.from("-100:5");

    private ApplicationEventPublisher publisher;
    private Release release;
    private SublibraryAssignmentHandler handler;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        ReleaseRepository repository = mock(ReleaseRepository.class);
        release = new Release();
        release.setId(173L);
        release.setSublibrary("working");
        when(repository.findById(173L)).thenReturn(Optional.of(release));
        handler = new SublibraryAssignmentHandler(publisher, repository);
    }

    @Test
    void a_different_sublibrary_moves_the_release() {
        handler.handle(CTX, "LIB_ASSIGN:173:vault");

        verify(publisher).publishEvent(new MoveReleaseTaskEvent("-100:5", 173L, "vault"));
    }

    @Test
    void keeping_the_default_of_a_waiting_release_still_counts_as_the_choice() {
        release.setPlacementPending(true);

        handler.handle(CTX, "LIB_ASSIGN:173:working");

        verify(publisher).publishEvent(new PlaceReleaseTaskEvent("-100:5", 173L));
    }

    @Test
    void keeping_the_sublibrary_of_an_already_placed_release_does_nothing() {
        handler.handle(CTX, "LIB_ASSIGN:173:working");

        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
