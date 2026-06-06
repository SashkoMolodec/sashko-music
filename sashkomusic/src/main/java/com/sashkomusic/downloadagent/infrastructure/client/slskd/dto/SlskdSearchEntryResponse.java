package com.sashkomusic.downloadagent.infrastructure.client.slskd.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SlskdSearchEntryResponse(
        @JsonProperty("fileCount")
        int fileCount,

        List<SoulseekFile> files,

        @JsonProperty("hasFreeUploadSlot")
        boolean hasFreeUploadSlot,

        @JsonProperty("lockedFileCount")
        int lockedFileCount,

        @JsonProperty("lockedFiles")
        List<SoulseekFile> lockedFiles,

        @JsonProperty("queueLength")
        int queueLength,

        int token,

        @JsonProperty("uploadSpeed")
        long uploadSpeed,

        String username
) {

    /**
     * Individual file information from Soulseek user
     */
    public record SoulseekFile(
            Integer bitRate,

            @JsonProperty("bitDepth")
            Integer bitDepth,

            int code,

            String extension,

            String filename,

            @JsonProperty("isVariableBitRate")
            Boolean isVariableBitRate,

            Integer length,

            @JsonProperty("sampleRate")
            Integer sampleRate,

            long size,

            @JsonProperty("isLocked")
            boolean isLocked
    ) {

        /**
         * Get file format (FLAC, MP3, etc.)
         */
        public String getFormat() {
            if (extension != null && !extension.isEmpty()) {
                return extension.toUpperCase();
            }

            if (filename != null) {
                int lastDot = filename.lastIndexOf('.');
                if (lastDot > 0 && lastDot < filename.length() - 1) {
                    return filename.substring(lastDot + 1).toUpperCase();
                }
            }

            return "UNKNOWN";
        }

        /**
         * Check if file is lossless (FLAC)
         */
        public boolean isLossless() {
            String format = getFormat();
            return "FLAC".equals(format) || "ALAC".equals(format) || "WAV".equals(format);
        }

        /**
         * Check if file is high quality (320kbps MP3 or FLAC)
         */
        public boolean isHighQuality() {
            if (isLossless()) {
                return true;
            }
            return bitRate != null && bitRate >= 320;
        }

        /**
         * Get file size in MB
         */
        public int getSizeMB() {
            return (int) (size / (1024.0 * 1024.0));
        }

        /**
         * Get duration in minutes
         */
        public Double getDurationMinutes() {
            if (length == null) {
                return null;
            }
            return length / 60.0;
        }

        /**
         * Get just the filename without path
         */
        public String getFileName() {
            if (filename == null) {
                return null;
            }

            // Handle both Windows and Unix paths
            int lastSlash = Math.max(filename.lastIndexOf('\\'), filename.lastIndexOf('/'));
            if (lastSlash >= 0 && lastSlash < filename.length() - 1) {
                return filename.substring(lastSlash + 1);
            }

            return filename;
        }

        /**
         * Check if this is an audio file
         */
        public boolean isAudioFile() {
            String format = getFormat();
            return List.of("MP3", "FLAC", "WAV", "M4A", "AAC", "OGG", "ALAC", "APE", "WMA")
                    .contains(format);
        }

        /**
         * Check if this is an image file (cover art)
         */
        public boolean isImageFile() {
            String format = getFormat();
            return List.of("JPG", "JPEG", "PNG", "GIF", "BMP", "WEBP")
                    .contains(format);
        }
    }

    /**
     * Get total number of files (available + locked)
     */
    public int getTotalFileCount() {
        return fileCount + lockedFileCount;
    }

    /**
     * Get all files (available + locked)
     */
    public List<SoulseekFile> getAllFiles() {
        return List.of(
                        files != null ? files : List.<SoulseekFile>of(),
                        lockedFiles != null ? lockedFiles : List.<SoulseekFile>of()
                ).stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * Get only FLAC files
     */
    public List<SoulseekFile> getFlacFiles() {
        return getAllFiles().stream()
                .filter(SoulseekFile::isLossless)
                .toList();
    }

    /**
     * Get only high quality files (320kbps+ or FLAC)
     */
    public List<SoulseekFile> getHighQualityFiles() {
        return getAllFiles().stream()
                .filter(SoulseekFile::isHighQuality)
                .toList();
    }

    /**
     * Get only audio files (no images, nfo, etc.)
     */
    public List<SoulseekFile> getAudioFiles() {
        return getAllFiles().stream()
                .filter(SoulseekFile::isAudioFile)
                .toList();
    }

    /**
     * Get cover art files
     */
    public List<SoulseekFile> getCoverArtFiles() {
        return getAllFiles().stream()
                .filter(SoulseekFile::isImageFile)
                .toList();
    }

    /**
     * Get total size of all files in MB
     */
    public int getTotalSizeMB() {
        return getAllFiles().stream()
                .mapToInt(SoulseekFile::getSizeMB)
                .sum();
    }

    /**
     * Check if user has free upload slot
     */
    public boolean canDownload() {
        return hasFreeUploadSlot;
    }

    /**
     * Get upload speed in MB/s
     */
    public double getUploadSpeedMBps() {
        return uploadSpeed / (1024.0 * 1024.0);
    }
}
