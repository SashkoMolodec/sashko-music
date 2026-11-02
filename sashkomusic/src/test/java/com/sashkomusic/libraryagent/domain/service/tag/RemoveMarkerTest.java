package com.sashkomusic.libraryagent.domain.service.tag;

import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackTagRepository;
import com.sashkomusic.libraryagent.domain.service.utils.AudioTagExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoveMarkerTest {

    private final TrackRepository trackRepository = mock(TrackRepository.class);
    private final DjTagWriter writer = mock(DjTagWriter.class);
    private final RateTrackService service = new RateTrackService(
            trackRepository, mock(TrackTagRepository.class), mock(AudioTagExtractor.class), writer);

    @TempDir Path dir;

    @Test
    void drops_the_marker_from_the_db_comment_and_the_file() throws Exception {
        Path file = Files.createFile(dir.resolve("01.flac"));
        Track track = track(file, "tool; E3; (detroit techno); electronic, techno");

        var result = service.removeMarker(7L, "detroit techno");

        assertThat(result.success()).isTrue();
        assertThat(track.getTag("COMM")).contains("tool; E3; electronic, techno");
        verify(writer).removeMarker(file, "detroit techno");
        verify(trackRepository).save(track);
    }

    @Test
    void a_comment_that_was_only_the_marker_goes_away() {
        Track track = track(null, "(detroit techno)");

        service.removeMarker(7L, "detroit techno");

        assertThat(track.hasTag("COMM")).isFalse();
    }

    @Test
    void a_track_without_the_marker_stays_as_it_is() {
        Track track = track(null, "tool; (raw modern techno)");

        var result = service.removeMarker(7L, "detroit techno");

        assertThat(result.success()).isTrue();
        assertThat(track.getTag("COMM")).contains("tool; (raw modern techno)");
        verify(writer, never()).removeMarker(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private Track track(Path file, String comment) {
        Track track = new Track("Sharevari", 1);
        track.setId(7L);
        track.setLocalPath(file == null ? null : file.toString());
        track.setTag("COMM", comment);
        when(trackRepository.findByIdWithLock(7L)).thenReturn(Optional.of(track));
        return track;
    }
}
