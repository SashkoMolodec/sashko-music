package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.TrackMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackMatcherWhitespaceTest {

    @TempDir
    Path dir;

    @Test
    void addsSpacesAroundSeparatorDashButKeepsNumericRanges() throws Exception {
        Path a = Files.createFile(dir.resolve("01. Надія Пащенко Та Ніна Злобіна– Весни Примари.flac"));
        Path b = Files.createFile(dir.resolve("02.  Назарій Яремчук Та Василь Зінкевич– Червона Рута.flac"));
        Path c = Files.createFile(dir.resolve("03. Hits 1990–1995.flac"));
        ReleaseMetadata metadata = new ReleaseMetadata(null, null, null, "Various", "Release", 0,
                List.of(), List.of(), 0, 0, 0, List.of(), null, List.of(), null);

        var matches = new TrackMatcher().match(List.of(a, b, c), metadata);

        assertThat(matches.get(a.toString()).trackTitle()).isEqualTo("Надія Пащенко Та Ніна Злобіна – Весни Примари");
        assertThat(matches.get(b.toString()).trackTitle()).isEqualTo("Назарій Яремчук Та Василь Зінкевич – Червона Рута");
        assertThat(matches.get(c.toString()).trackTitle()).isEqualTo("Hits 1990–1995");
    }

    @Test
    void stripsStrayWhitespaceFromFilenameDerivedTitles() throws Exception {
        Path file = Files.createFile(dir.resolve("02.  Назарій   Яремчук – Червона Рута .flac"));
        ReleaseMetadata metadata = new ReleaseMetadata(null, null, null, "Various", "Release", 0,
                List.of(), List.of(), 0, 0, 0, List.of(), null, List.of(), null);

        TrackMatch match = new TrackMatcher().match(List.of(file), metadata).get(file.toString());

        assertThat(match.trackTitle()).isEqualTo("Назарій Яремчук – Червона Рута");
    }

    @Test
    void matchesUnnumberedFilesToOfficialTracksByTitle() throws Exception {
        Path a = Files.createFile(dir.resolve("Надія Пащенко Та Ніна Злобіна– Весни Примари.flac"));
        Path b = Files.createFile(dir.resolve("Надія Пащенко– Коло Твого Двору.flac"));
        Path c = Files.createFile(dir.resolve("Надія Пащенко Та Ніна Злобіна – Зелен Клен.flac"));
        Path d = Files.createFile(dir.resolve(" Назарій Яремчук Та Василь Зінкевич– Червона Рута.flac"));
        ReleaseMetadata metadata = new ReleaseMetadata(null, null, null, "Various", "Release", 0,
                List.of(), List.of(), 0, 0, 0, List.of(
                new TrackMetadata(1, "Надія Пащенко Та Ніна Злобіна", "Зелен Клен"),
                new TrackMetadata(2, "Надія Пащенко", "Коло Твого Двору"),
                new TrackMetadata(3, "Надія Пащенко Та Ніна Злобіна", "Весни Примари"),
                new TrackMetadata(4, "Назарій Яремчук Та Василь Зінкевич", "Червона Рута")),
                null, List.of(), null);

        var matches = new TrackMatcher().match(List.of(a, b, c, d), metadata);

        assertThat(matches.get(c.toString())).isEqualTo(new TrackMatch(1, "Надія Пащенко Та Ніна Злобіна", "Зелен Клен"));
        assertThat(matches.get(b.toString())).isEqualTo(new TrackMatch(2, "Надія Пащенко", "Коло Твого Двору"));
        assertThat(matches.get(a.toString())).isEqualTo(new TrackMatch(3, "Надія Пащенко Та Ніна Злобіна", "Весни Примари"));
        assertThat(matches.get(d.toString())).isEqualTo(new TrackMatch(4, "Назарій Яремчук Та Василь Зінкевич", "Червона Рута"));
    }
}
