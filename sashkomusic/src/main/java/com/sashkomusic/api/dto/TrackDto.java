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
}
