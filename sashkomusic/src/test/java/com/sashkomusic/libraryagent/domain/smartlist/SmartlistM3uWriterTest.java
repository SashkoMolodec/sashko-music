package com.sashkomusic.libraryagent.domain.smartlist;

import com.sashkomusic.libraryagent.config.LibraryConfig;
import com.sashkomusic.libraryagent.domain.entity.Artist;
import com.sashkomusic.libraryagent.domain.entity.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SmartlistM3uWriterTest {

    @TempDir
    Path tempDir;

    SmartlistM3uWriter writer;

    @BeforeEach
    void setUp() {
        LibraryConfig config = new LibraryConfig();
        config.setRootPath(tempDir.toString());
        writer = new SmartlistM3uWriter(config);
    }

    @Test
    void writes_m3u_with_extinf_and_relative_paths() throws Exception {
        Path libraryRoot = tempDir;
        Path mp3 = libraryRoot.resolve("working/Artist/Album/01. song.mp3");
        Files.createDirectories(mp3.getParent());
        Files.createFile(mp3);

        Track t = track("Song Title", 180, mp3.toString(), "Artist Name");
        var result = writer.write("my list", List.of(t));

        assertThat(result.m3uPath()).exists();
        assertThat(result.m3uPath().getFileName().toString()).isEqualTo("my list.m3u");
        assertThat(result.writtenCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(0);
        String content = Files.readString(result.m3uPath(), StandardCharsets.UTF_8);
        assertThat(content).startsWith("#EXTM3U");
        assertThat(content).contains("#EXTINF:180,Artist Name - Song Title");
        assertThat(content).contains("../working/Artist/Album/01. song.mp3");
    }

    @Test
    void skips_tracks_with_stale_local_path() throws Exception {
        Path mp3 = tempDir.resolve("working/Artist/Album/01. song.mp3");
        Files.createDirectories(mp3.getParent());
        Files.createFile(mp3);

        Track good = track("Good Track", 180, mp3.toString(), "Artist");
        Track stale = track("Missing Track", 200, tempDir.resolve("ghost/missing.mp3").toString(), "Ghost");

        var result = writer.write("test", List.of(good, stale));

        assertThat(result.writtenCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        String content = Files.readString(result.m3uPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("Good Track");
        assertThat(content).doesNotContain("Missing Track");
    }

    @Test
    void rename_moves_file() throws Exception {
        writer.write("old", List.of());
        assertThat(tempDir.resolve("smartlists/old.m3u")).exists();

        assertThat(writer.rename("old", "new")).isTrue();
        assertThat(tempDir.resolve("smartlists/old.m3u")).doesNotExist();
        assertThat(tempDir.resolve("smartlists/new.m3u")).exists();
    }

    @Test
    void delete_removes_file() throws Exception {
        writer.write("doomed", List.of());
        Path file = tempDir.resolve("smartlists/doomed.m3u");
        assertThat(file).exists();

        assertThat(writer.delete("doomed")).isTrue();
        assertThat(file).doesNotExist();
        assertThat(writer.delete("doomed")).isFalse();
    }

    @Test
    void sanitizes_unsafe_chars_in_name() throws Exception {
        var result = writer.write("foo/bar:baz", List.of());
        assertThat(result.m3uPath().getFileName().toString()).isEqualTo("foo_bar_baz.m3u");
    }

    private Track track(String title, int duration, String localPath, String artistName) {
        Track t = new Track(title, 1);
        t.setDuration(duration);
        t.setLocalPath(localPath);
        Set<Artist> artists = new HashSet<>();
        artists.add(new Artist(artistName));
        t.setArtists(artists);
        return t;
    }
}
