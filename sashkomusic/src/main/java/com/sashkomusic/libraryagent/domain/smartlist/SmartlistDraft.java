package com.sashkomusic.libraryagent.domain.smartlist;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record SmartlistDraft(String name, SmartlistDsl dsl, String originalName) {
    public static final String FLOW_KEY = "smartlist_create";

    /** New-smartlist draft — no existing entity backs it. */
    public SmartlistDraft(String name, SmartlistDsl dsl) {
        this(name, dsl, null);
    }

    /** True when this draft edits an already-existing smartlist rather than creating a new one. */
    @JsonIgnore
    public boolean isEdit() {
        return originalName != null;
    }

    public SmartlistDraft withDsl(SmartlistDsl newDsl) {
        return new SmartlistDraft(name, newDsl, originalName);
    }
}
