package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.client.AudioAnalyzerClient;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.ArtistRepository;
import com.sashkomusic.libraryagent.domain.repository.LabelRepository;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TagRepository;
import com.sashkomusic.libraryagent.domain.service.utils.AudioTagExtractor;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.SearchEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Analysis and the Apple sync read files by path, and the user's sub-library choice moves the release.
 * A freshly processed release therefore waits for that choice and is analysed afterwards, from the
 * path the database holds then; a reprocessed release already lies where it belongs and goes at once.
 */
class ReleaseAnalysisTriggerTest {

    private ReleaseRepository releaseRepository;
    private AudioAnalyzerClient analyzer;
    private ReleaseService releaseService;
    private Release saved;

    @BeforeEach
    void setUp() {
        releaseRepository = mock(ReleaseRepository.class);
        analyzer = mock(AudioAnalyzerClient.class);
        ArtistRepository artistRepository = mock(ArtistRepository.class);
        TagRepository tagRepository = mock(TagRepository.class);

        when(releaseRepository.findBySourceId(anyString())).thenReturn(Optional.empty());
        when(artistRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(artistRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tagRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        saved = new Release();
        saved.setId(42L);
        saved.setTitle("Spirit Of Love");
        Track track = new Track("Intro", 1);
        track.setLocalPath("/lib/vault/symphony of love/01. intro.flac");
        saved.addTrack(track);
        when(releaseRepository.saveAndFlush(any())).thenReturn(saved);
        when(releaseRepository.findById(42L)).thenReturn(Optional.of(saved));

        releaseService = new ReleaseService(
                releaseRepository, artistRepository, tagRepository, mock(LabelRepository.class),
                mock(AudioTagExtractor.class), analyzer, mock(LibrarySearchService.class));
    }

    private static ReleaseMetadata metadata() {
        return new ReleaseMetadata(
                "src-1", null, SearchEngine.DISCOGS,
                "Symphony Of Love", "Spirit Of Love",
                0, List.of("1993"), List.of(), 0, 0, 0,
                List.of(), null, List.of("Trance"), null);
    }

    @Test
    void a_new_release_waits_for_its_sublibrary_instead_of_being_analysed() {
        releaseService.saveRelease(metadata(), "/lib/working/symphony of love", null, List.of(), 1, "working", true);

        ArgumentCaptor<Release> persisted = ArgumentCaptor.forClass(Release.class);
        verify(releaseRepository).saveAndFlush(persisted.capture());
        assertThat(persisted.getValue().isPlacementPending()).isTrue();
        verify(analyzer, never()).requestAnalysis(any());
    }

    @Test
    void a_reprocessed_release_is_analysed_at_once() {
        releaseService.saveRelease(metadata(), "/lib/vault/symphony of love", null, List.of(), 2, null, false);

        ArgumentCaptor<Release> persisted = ArgumentCaptor.forClass(Release.class);
        verify(releaseRepository).saveAndFlush(persisted.capture());
        assertThat(persisted.getValue().isPlacementPending()).isFalse();
        verify(analyzer).requestAnalysis(any());
    }

    @Test
    void requestAnalysis_sends_the_tracks_from_the_paths_the_database_holds() {
        releaseService.requestAnalysis(42L);

        var request = ArgumentCaptor.forClass(com.sashkomusic.libraryagent.client.dto.AnalyzeTrackRequest.class);
        verify(analyzer).requestAnalysis(request.capture());
        assertThat(request.getValue().localPath()).isEqualTo("/lib/vault/symphony of love/01. intro.flac");
    }
}
