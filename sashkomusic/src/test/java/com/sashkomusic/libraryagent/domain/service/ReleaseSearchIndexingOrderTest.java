package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.client.AudioAnalyzerClient;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.repository.ArtistRepository;
import com.sashkomusic.libraryagent.domain.repository.LabelRepository;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TagRepository;
import com.sashkomusic.libraryagent.domain.service.utils.AudioTagExtractor;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LibrarySearchService.indexRelease reads release_artists / release_tags over raw JDBC, which does
 * not trigger a Hibernate auto-flush. Those are @ManyToMany join tables, so unlike the IDENTITY-keyed
 * releases and tracks rows they are not written at persist time — the release must be flushed first
 * or it gets indexed without its artist and genre tags, and becomes unfindable by artist name.
 */
class ReleaseSearchIndexingOrderTest {

    private ReleaseRepository releaseRepository;
    private LibrarySearchService librarySearchService;
    private ReleaseService releaseService;

    @BeforeEach
    void setUp() {
        releaseRepository = mock(ReleaseRepository.class);
        librarySearchService = mock(LibrarySearchService.class);
        ArtistRepository artistRepository = mock(ArtistRepository.class);
        TagRepository tagRepository = mock(TagRepository.class);

        when(releaseRepository.findBySourceId(anyString())).thenReturn(Optional.empty());
        when(artistRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(artistRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tagRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Release saved = new Release();
        saved.setId(42L);
        when(releaseRepository.saveAndFlush(any())).thenReturn(saved);
        when(releaseRepository.save(any())).thenReturn(saved);

        releaseService = new ReleaseService(
                releaseRepository,
                artistRepository,
                tagRepository,
                mock(LabelRepository.class),
                mock(AudioTagExtractor.class),
                mock(AudioAnalyzerClient.class),
                librarySearchService
        );
    }

    private static ReleaseMetadata metadata() {
        return new ReleaseMetadata(
                "src-1", null, SearchEngine.DISCOGS,
                "Symphony Of Love", "Spirit Of Love",
                0, List.of("1993"), List.of(), 0, 0, 0,
                List.of(), null, List.of("Trance"), null
        );
    }

    @Test
    void flushes_the_release_before_building_its_search_vector() {
        releaseService.saveRelease(metadata(), "/lib/working/symphony of love", null, List.of(), 1, null);

        InOrder order = inOrder(releaseRepository, librarySearchService);
        order.verify(releaseRepository).saveAndFlush(any(Release.class));
        order.verify(librarySearchService).indexRelease(42L);
    }

    @Test
    void does_not_index_off_a_plain_save_whose_join_table_rows_are_still_pending() {
        releaseService.saveRelease(metadata(), "/lib/working/symphony of love", null, List.of(), 1, null);

        verify(releaseRepository, never()).save(any(Release.class));
    }
}
