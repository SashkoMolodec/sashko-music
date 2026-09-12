package com.sashkomusic.shared.task;

public record ReprocessOptions(
        boolean skipRetag,
        boolean force
) {
    public static ReprocessOptions parse(String argument) {
        boolean skipRetag = argument.contains("--skip-retag");
        boolean force = argument.contains("--force");
        return new ReprocessOptions(skipRetag, force);
    }

    public static ReprocessOptions defaults() {
        return new ReprocessOptions(false, false);
    }
}
