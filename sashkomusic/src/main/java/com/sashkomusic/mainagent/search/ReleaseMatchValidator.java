package com.sashkomusic.mainagent.search;

import com.sashkomusic.shared.model.MetadataSearchRequest;
import com.sashkomusic.shared.model.ReleaseMetadata;
import com.sashkomusic.shared.model.TrackMetadata;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a search result actually IS what was asked for.
 * <p>
 * Every engine's "search" is really a relevance ranker, not a filter: Discogs' {@code artist=} param
 * is a full-text hint that happily returns same-name artists, and a {@code track=} lookup returns
 * every release carrying a track by that name regardless of who performed it. Asking for
 * "Adjust (BE) — Mist" that way yields ~49 unrelated releases named "Mist". This class is the gate
 * that turns those piles into a pinpoint answer: a result survives only if the artist lines up AND
 * either the release title or one of its tracks is the thing that was requested.
 */
public final class ReleaseMatchValidator {

    private ReleaseMatchValidator() {}

    /** How strong the evidence is that this release is the requested one. {@code NONE} = drop it. */
    public enum Match {
        /** Title is exactly the requested release/track (and the artist agrees). */
        EXACT,
        /** The requested track sits on this release's tracklist — how compilations qualify. */
        TRACK,
        /** Title contains the requested one (edition/reissue/extended naming), artist agrees. */
        PARTIAL,
        NONE;

        public boolean isHit() {
            return this != NONE;
        }
    }

    private static final Set<String> VARIOUS_ARTISTS = Set.of("various", "various artists", "va", "v a", "unknown artist");

    public static Match evaluate(MetadataSearchRequest request, ReleaseMetadata release) {
        String wantedArtist = safe(request.artist());
        String wantedAlbum = safe(request.release());
        String wantedTrack = safe(request.recording());

        boolean artistAsked = !wantedArtist.isBlank();
        boolean titleAsked = !wantedAlbum.isBlank() || !wantedTrack.isBlank();

        // Browse query ("trance 1994") — style/year filters were applied by the engine itself and
        // there is no name to check anything against, so everything the engine returned stands.
        if (!artistAsked && !titleAsked) {
            return Match.PARTIAL;
        }

        boolean artistOnRelease = artistAsked && artistMatches(release.artist(), wantedArtist);
        boolean compilation = isVariousArtists(release.artist());
        boolean trackHit = !wantedTrack.isBlank() && hasTrack(release.tracks(), wantedTrack, artistAsked ? wantedArtist : null);

        if (!titleAsked) {
            // Artist-only query: the whole discography is a legitimate answer.
            return artistOnRelease ? Match.EXACT : Match.NONE;
        }

        // A wrong-artist release is wrong no matter how well the title matches. "Various" is the one
        // exception — on a compilation the album artist says nothing about who plays which track.
        if (artistAsked && !artistOnRelease && !compilation && !trackHit) {
            return Match.NONE;
        }
        boolean artistOk = !artistAsked || artistOnRelease || trackHit;

        String title = release.title();
        if (artistOk && (titlesEqual(title, wantedAlbum) || titlesEqual(title, wantedTrack))) {
            return Match.EXACT;
        }
        if (trackHit) {
            return Match.TRACK;
        }
        if (artistOnRelease && (titleContains(title, wantedAlbum) || titleContains(title, wantedTrack))) {
            return Match.PARTIAL;
        }
        return Match.NONE;
    }

    /**
     * True when the only thing standing between this release and a verdict is its tracklist, which
     * search endpoints never return. Callers use it to decide which candidates are worth one extra
     * API round-trip — a compilation like "Radio Universe - Ultima" can only be recognised as the
     * home of "Adjust (BE) — Mist" after its tracks are loaded.
     */
    public static boolean needsTracklistCheck(MetadataSearchRequest request, ReleaseMetadata release) {
        if (safe(request.recording()).isBlank()) return false;
        if (release.tracks() != null && !release.tracks().isEmpty()) return false;
        return evaluate(request, release) == Match.NONE;
    }

    public static boolean artistMatches(String found, String wanted) {
        String a = normalize(found);
        String b = normalize(wanted);
        if (a.isBlank() || b.isBlank()) return false;
        return a.equals(b) || containsWord(a, b) || containsWord(b, a);
    }

    public static boolean isVariousArtists(String artist) {
        return VARIOUS_ARTISTS.contains(normalize(artist));
    }

    private static boolean hasTrack(List<TrackMetadata> tracks, String wantedTrack, String wantedArtist) {
        if (tracks == null || tracks.isEmpty()) return false;
        return tracks.stream().anyMatch(track -> {
            if (!titlesEqual(track.title(), wantedTrack)) return false;
            // On a compilation the per-track artist is the only place the requested artist appears.
            return wantedArtist == null || artistMatches(track.artist(), wantedArtist);
        });
    }

    private static boolean titlesEqual(String found, String wanted) {
        if (wanted == null || wanted.isBlank()) return false;
        return normalize(found).equals(normalize(wanted));
    }

    private static boolean titleContains(String found, String wanted) {
        if (wanted == null || wanted.isBlank()) return false;
        return containsWord(normalize(found), normalize(wanted));
    }

    /** Whole-word containment — "mist" must not match "mistake" or "optimistic". */
    private static boolean containsWord(String haystack, String needle) {
        if (haystack.isBlank() || needle.isBlank()) return false;
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    /**
     * Lowercase, drop bracketed qualifiers, strip punctuation. Handles both the disambiguation
     * Discogs appends to colliding artist names ("Adjust (2)") and the country/label suffix users
     * type ("Adjust (BE)") — both normalise to "adjust", which is what makes them comparable.
     */
    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
