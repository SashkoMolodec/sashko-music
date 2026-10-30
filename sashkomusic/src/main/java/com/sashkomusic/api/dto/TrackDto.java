package com.sashkomusic.api.dto;

public record TrackDto(
        Long id,
        String path,
        String title,
        String artistName,
        String rating,
        String djEnergy,
        String djFunction,
        String comment
) {
    public static TrackDto of(Long id, String path, String title, String artistName, String rating,
                              String djEnergy, String djFunction, String comment) {
        return new TrackDto(id, path, title, artistName, rating, djEnergy, djFunction, comment);
    }

    public static TrackDto empty() {
        return new TrackDto(null, null, null, null, null, null, null, null);
    }

    /**
     * {@code rating} is stored in the WMP/Traktor scale (1★=51 … 5★=255) because that is what the
     * audio files carry. Nothing outside the tag layer should have to know that, so read stars here.
     *
     * @return 0 when unrated or unparseable, otherwise 1..5
     */
    public int stars() {
        return starsOf(rating);
    }

    /** Same WMP-to-stars mapping for callers that only have the raw tag value. */
    public static int starsOf(String rawRating) {
        if (rawRating == null || rawRating.isBlank()) return 0;
        try {
            int raw = Integer.parseInt(rawRating.trim());
            if (raw <= 0) return 0;
            return raw <= 51 ? 1 : raw <= 102 ? 2 : raw <= 153 ? 3 : raw <= 204 ? 4 : 5;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
