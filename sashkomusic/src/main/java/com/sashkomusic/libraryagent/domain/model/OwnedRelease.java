package com.sashkomusic.libraryagent.domain.model;

/** A release in the user's library as discovery sees it: who and what, nothing else. {@code artists} is ", "-joined. */
public record OwnedRelease(String artists, String title) {}
