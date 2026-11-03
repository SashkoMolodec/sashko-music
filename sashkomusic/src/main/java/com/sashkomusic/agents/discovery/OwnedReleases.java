package com.sashkomusic.agents.discovery;

import com.sashkomusic.libraryagent.domain.model.OwnedRelease;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the user already has, for discovery to leave out. Names match loosely — case, diacritics, bracketed
 * extras and "the" do not count — because the library stores "enter (deluxe edition) (2023) [flac]"-style
 * titles while a recommendation says "Enter".
 */
final class OwnedReleases {

    private final List<OwnedRelease> releases;
    /** "artist|title" for every artist of every release. */
    private final Set<String> keys = new HashSet<>();

    private OwnedReleases(List<OwnedRelease> releases) {
        this.releases = releases;
        for (OwnedRelease release : releases) {
            String title = normalize(release.title());
            if (title.isEmpty()) continue;
            keys.add(normalize(release.artists()) + "|" + title);
            for (String artist : release.artists().split(",")) {
                keys.add(normalize(artist) + "|" + title);
            }
        }
    }

    static OwnedReleases of(List<OwnedRelease> releases) {
        return new OwnedReleases(releases == null ? List.of() : releases);
    }

    boolean owns(String artist, String title) {
        return keys.contains(normalize(artist) + "|" + normalize(title));
    }

    /** One "Artist — Title" line per release, for the recommender to read the user's taste from. */
    String forPrompt() {
        if (releases.isEmpty()) return "(nothing yet)";
        return releases.stream()
                .map(r -> (r.artists().isBlank() ? "?" : r.artists()) + " — " + r.title())
                .collect(Collectors.joining("\n"));
    }

    static String normalize(String name) {
        if (name == null) return "";
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String words = plain.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
                .replace("&", " and ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return Arrays.stream(words.split(" "))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.joining(" "))
                .replaceFirst("^the ", "");
    }
}
