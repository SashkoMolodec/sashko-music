package com.sashkomusic.libraryagent.domain.model;

import java.time.LocalDateTime;

public record TagChange(
        String tagName,
        String oldValue,
        String newValue,
        LocalDateTime changedAt
) {
    public TagChange(String tagName, String oldValue, String newValue) {
        this(tagName, oldValue, newValue, LocalDateTime.now());
    }

    public boolean isNewTag() {
        return oldValue == null;
    }
}
