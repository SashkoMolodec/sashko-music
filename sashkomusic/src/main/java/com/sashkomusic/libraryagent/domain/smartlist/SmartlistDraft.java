package com.sashkomusic.libraryagent.domain.smartlist;

public record SmartlistDraft(String name, SmartlistDsl dsl) {
    public static final String FLOW_KEY = "smartlist_create";
}
