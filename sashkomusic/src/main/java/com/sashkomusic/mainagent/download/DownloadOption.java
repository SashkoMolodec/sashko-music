package com.sashkomusic.mainagent.download;

import java.util.List;
import java.util.Map;

public record DownloadOption(
        String id,
        DownloadEngine source,
        String displayName,
        int totalSize,
        List<FileItem> files,
        Map<String, String> technicalMetadata
) {
    public record FileItem(
            String filename,
            long size,
            Integer bitRate,
            Integer bitDepth,
            Integer sampleRate,
            int lengthSeconds
    ) {

        public String displayName() {
            if (filename == null) {
                return null;
            }

            int lastSlash = Math.max(filename.lastIndexOf('\\'), filename.lastIndexOf('/'));
            if (lastSlash >= 0 && lastSlash < filename.length() - 1) {
                return filename.substring(lastSlash + 1);
            }
            return filename;
        }
    }
}