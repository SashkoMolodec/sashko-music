package com.sashkomusic.downloadagent.domain.exception;

public class MusicDownloadException extends RuntimeException {

    public MusicDownloadException(String message) {
        super(message);
    }

    public MusicDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}