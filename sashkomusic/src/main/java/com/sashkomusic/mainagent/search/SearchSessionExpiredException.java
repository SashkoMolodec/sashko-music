package com.sashkomusic.mainagent.search;

public class SearchSessionExpiredException extends RuntimeException {
    public SearchSessionExpiredException(String message) {
        super(message);
    }
}
