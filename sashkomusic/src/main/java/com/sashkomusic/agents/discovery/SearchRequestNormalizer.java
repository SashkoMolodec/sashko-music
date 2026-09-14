package com.sashkomusic.agents.discovery;

import com.sashkomusic.shared.model.MetadataSearchRequest;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Repairs the two things the extractor LLM gets wrong often enough to break a search outright.
 * <p>
 * Both are stated as rules in {@link SearchRequestExtractor}'s prompt and both are ignored in
 * practice, so they are settled here instead: a prompt is a suggestion, this is the guarantee.
 * <ol>
 *   <li><b>{@code (BE)} is not a country.</b> "Adjust (BE)" is how Discogs — and the user copying
 *       from Discogs — disambiguates artists that share a name. The model reads the bracket as a
 *       country filter, and every engine is then asked for a <em>Belgian pressing</em> that does
 *       not exist. A genuine country ask is worded ("German techno", "releases from Japan"), never
 *       bracketed, so a code that appears in the query as "(XX)" is dropped.</li>
 *   <li><b>"Artist — Title" is also a track.</b> With no album/track word in the query the title
 *       is equally likely to be either, and the prompt asks for both fields to be filled. When the
 *       model fills only {@code release}, the track lookup path never runs: Discogs' free-text
 *       {@code q} search — the only one that reaches a compilation's tracklist — is gated on
 *       {@code recording}, and so is the tracklist confirmation pass. "Adjust (BE) - Fractured
 *       Elements" lives on a Various-artists compilation and was unfindable for exactly that
 *       reason.</li>
 * </ol>
 */
final class SearchRequestNormalizer {

    private SearchRequestNormalizer() {}

    /** Words that say the title names an album, so it must not be searched as a track as well. */
    private static final Pattern RELEASE_INDICATOR = Pattern.compile(
            "\\b(album|альбом\\w*|lp|ep|compilation|компіляці\\w*|збірк\\w*|vinyl|вініл\\w*|платівк\\w*|cd)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Words that say the title names a track — these win over a release word in the same query. */
    private static final Pattern TRACK_INDICATOR = Pattern.compile(
            "\\b(track|трек\\w*|song|пісн\\w*|сингл\\w*|single)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    static MetadataSearchRequest normalize(String rawQuery, MetadataSearchRequest request) {
        if (request == null) return null;
        String raw = rawQuery == null ? "" : rawQuery;
        return fillRecordingForBareTitle(raw, dropBracketedCountry(raw, request));
    }

    private static MetadataSearchRequest dropBracketedCountry(String raw, MetadataSearchRequest request) {
        String country = request.country();
        if (country == null || country.isBlank()) return request;
        String bracketed = "(" + country.toLowerCase(Locale.ROOT) + ")";
        if (!raw.toLowerCase(Locale.ROOT).contains(bracketed)) return request;
        return request.withCountry("");
    }

    private static MetadataSearchRequest fillRecordingForBareTitle(String raw, MetadataSearchRequest request) {
        if (!request.recording().isBlank() || request.release().isBlank()) return request;
        boolean askedForATrack = TRACK_INDICATOR.matcher(raw).find();
        if (!askedForATrack && RELEASE_INDICATOR.matcher(raw).find()) return request;
        return request.withRecording(request.release());
    }
}
