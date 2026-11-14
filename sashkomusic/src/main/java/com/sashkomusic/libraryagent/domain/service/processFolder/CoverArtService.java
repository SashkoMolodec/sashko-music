package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.shared.model.ReleaseMetadata;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class CoverArtService {

    private final RestClient.Builder restClientBuilder;
    private final CoverArtService self;

    public CoverArtService(RestClient.Builder restClientBuilder, @Lazy CoverArtService self) {
        this.restClientBuilder = restClientBuilder;
        this.self = self;
    }

    @CircuitBreaker(name = "coverArtClient", fallbackMethod = "downloadCoverFallback")
    @Retry(name = "coverArtClient")
    public void downloadCover(String coverUrl, Path albumDirectory) {
        if (coverUrl == null || coverUrl.isEmpty()) {
            log.warn("No cover URL provided, skipping cover art download");
            return;
        }

        log.info("Downloading cover art from: {}", coverUrl);

        RestClient client = restClientBuilder.build();

        byte[] imageData = client.get()
                .uri(coverUrl)
                .retrieve()
                .body(byte[].class);

        if (imageData == null || imageData.length == 0) {
            throw new CoverArtDownloadException("Empty response from cover URL: " + coverUrl);
        }

        // Validate it's actually an image
        if (!isValidImageData(imageData)) {
            throw new CoverArtDownloadException("Downloaded data is not a valid image (first bytes: "
                    + bytesToHex(imageData, Math.min(8, imageData.length)) + ")");
        }

        Path coverPath = albumDirectory.resolve("cover.jpg");
        try {
            Files.write(coverPath, imageData);
        } catch (IOException ex) {
            throw new CoverArtDownloadException("Error saving cover art to disk: " + coverPath, ex);
        }

        log.info("Cover art saved successfully: {} ({} bytes)", coverPath, imageData.length);
    }

    private void downloadCoverFallback(String coverUrl, Path albumDirectory, Exception ex) {
        log.error("Failed to download cover art from {} after retries: {}", coverUrl, ex.getMessage());
    }

    public static class CoverArtDownloadException extends RuntimeException {
        public CoverArtDownloadException(String message) {
            super(message);
        }

        public CoverArtDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private boolean isValidImageData(byte[] data) {
        if (data.length < 4) {
            return false;
        }
        // Check for common image file signatures
        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47
        if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50 &&
            data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return true;
        }
        return false;
    }

    private String bytesToHex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    public byte[] getCoverArt(ReleaseMetadata metadata, String directoryPath) {
        Path albumDir = Paths.get(directoryPath);
        Path coverPath = albumDir.resolve("cover.jpg");

        if (Files.exists(coverPath)) {
            log.debug("Cover art already exists at: {}, skipping download", coverPath);
            try {
                return Files.readAllBytes(coverPath);
            } catch (IOException ex) {
                log.error("Error reading existing cover art from {}: {}", coverPath, ex.getMessage());
            }
        }

        self.downloadCover(metadata.coverUrl(), albumDir);

        if (!Files.exists(coverPath)) {
            log.warn("Cover art not found at: {}", coverPath);
            return null;
        }

        try {
            return Files.readAllBytes(coverPath);
        } catch (IOException ex) {
            log.error("Error reading cover art from {}: {}", coverPath, ex.getMessage());
            return null;
        }
    }
}