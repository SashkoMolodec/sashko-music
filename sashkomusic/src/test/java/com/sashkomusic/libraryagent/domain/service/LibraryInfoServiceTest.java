package com.sashkomusic.libraryagent.domain.service;

import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Label;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Tag;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.entity.TrackAnalysis;
import com.sashkomusic.libraryagent.domain.entity.TrackTag;
import com.sashkomusic.libraryagent.domain.repository.ReleaseRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackAnalysisRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LibraryInfoServiceTest {

    private final ReleaseRepository releaseRepository = mock(ReleaseRepository.class);
    private final TrackRepository trackRepository = mock(TrackRepository.class);
    private final TrackTagRepository trackTagRepository = mock(TrackTagRepository.class);
    private final TrackAnalysisRepository trackAnalysisRepository = mock(TrackAnalysisRepository.class);
    private final LibraryInfoService sut = new LibraryInfoService(
            releaseRepository, trackRepository, trackTagRepository, trackAnalysisRepository);

    private Release release;
    private Track analyzed;
    private Track plain;

    @BeforeEach
    void setUp() {
        release = new Release();
        release.setId(7L);
        release.setTitle("Untrue");
        release.setInitialRelease(2007);
        Artist burial = new Artist();
        burial.setName("Burial");
        release.getArtists().add(burial);
        Label label = new Label();
        label.setName("Hyperdub");
        release.setLabel(label);
        Tag tag = new Tag();
        tag.setName("dubstep");
        release.getTags().add(tag);

        analyzed = track(1L, "Archangel", 3);
        plain = track(2L, "Near Dark", 4);
        when(releaseRepository.findById(7L)).thenReturn(Optional.of(release));
        when(trackRepository.findByReleaseIdOrderByTrackNumberAsc(7L)).thenReturn(List.of(analyzed, plain));
    }

    @Test
    void release_info_combines_metadata_dj_tags_and_audio_average() {
        when(trackTagRepository.findAllByTrackIds(anyList())).thenReturn(List.of(
                tag(analyzed, "RATING", "204"), tag(analyzed, "DJ_ENERGY", "E4"), tag(analyzed, "COMM", "dark")));
        TrackAnalysis analysis = new TrackAnalysis(analyzed);
        analysis.setBpm(new BigDecimal("140.00"));
        analysis.setDanceability(new BigDecimal("1.2000"));
        when(trackAnalysisRepository.findByTrackIdIn(anyCollection())).thenReturn(List.of(analysis));

        String info = sut.releaseInfo(7L).orElseThrow();

        assertThat(info).contains("Burial — Untrue (2007)", "лейбл: Hyperdub", "жанри: dubstep", "треків: 2",
                "bpm 140", "рейтинг 4/5", "енергія E4", "комент dark");
        assertThat(info).contains("Near Dark");
    }

    @Test
    void unanalyzed_release_is_reported_not_invented() {
        when(trackTagRepository.findAllByTrackIds(anyList())).thenReturn(List.of());
        when(trackAnalysisRepository.findByTrackIdIn(anyCollection())).thenReturn(List.of());

        assertThat(sut.releaseInfo(7L).orElseThrow()).contains("нема аналізу");
    }

    @Test
    void unknown_release_is_empty() {
        when(releaseRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(sut.releaseInfo(99L)).isEmpty();
    }

    private Track track(long id, String title, int number) {
        Track t = new Track(title, number);
        t.setId(id);
        t.setRelease(release);
        return t;
    }

    private TrackTag tag(Track t, String name, String value) {
        return new TrackTag(t, name, value);
    }
}
