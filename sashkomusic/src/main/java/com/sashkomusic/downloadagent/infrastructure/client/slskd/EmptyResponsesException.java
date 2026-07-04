package com.sashkomusic.downloadagent.infrastructure.client.slskd;

public class EmptyResponsesException extends RuntimeException {
    public EmptyResponsesException(String message) {
        super(message);
    }
}
