package com.sashkomusic.mainagent.shared.model;

public record DateRange(Integer from, Integer to) {

    public static DateRange single(Integer year) {
        return new DateRange(year, year);
    }

    public static DateRange range(Integer from, Integer to) {
        return new DateRange(from, to);
    }

    public static DateRange empty() {
        return new DateRange(null, null);
    }

    public boolean isEmpty() {
        return from == null && to == null;
    }

    public boolean isSingleYear() {
        return from != null && from.equals(to);
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "";
        }
        if (isSingleYear()) {
            return String.valueOf(from);
        }
        return from + "-" + to;
    }

    public String toMusicBrainzQuery() {
        if (isEmpty()) {
            return "";
        }
        if (isSingleYear()) {
            return "date:" + from;
        }
        return "date:[" + from + " TO " + to + "]";
    }

    public String toDiscogsParam() {
        if (isEmpty()) {
            return "";
        }
        if (isSingleYear()) {
            return String.valueOf(from);
        }
        return from + "-" + to;
    }
}
