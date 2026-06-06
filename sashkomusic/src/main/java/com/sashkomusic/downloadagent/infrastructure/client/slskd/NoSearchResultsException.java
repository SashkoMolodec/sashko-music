package com.sashkomusic.downloadagent.infrastructure.client.slskd;

public class NoSearchResultsException extends RuntimeException {
    public NoSearchResultsException(String message) {
        super(message);
    }
}
