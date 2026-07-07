package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.downloadagent.infrastructure.client.slskd.SlskdClient;
import com.sashkomusic.downloadagent.infrastructure.client.slskd.dto.SlskdDirectoryDto;
import com.sashkomusic.mainagent.download.DownloadEngine;
import com.sashkomusic.mainagent.download.DownloadOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class SoulseekDirectoryService {

    private final SlskdClient slskdClient;

    /**
     * Fetches the full directory listing for a Soulseek option and returns a new DownloadOption
     * with the complete file set (audio + covers). Full filename paths are reconstructed by
     * prepending the albumFolder from the original option's metadata.
     */
    public DownloadOption fetchExpandedOption(DownloadOption original) {
        String username = original.technicalMetadata().get("username");
        String albumFolder = original.technicalMetadata().get("albumFolder");

        List<SlskdDirectoryDto> dirs = slskdClient.fetchDirectory(username, albumFolder);

        if (dirs == null || dirs.isEmpty()) {
            log.warn("Directory fetch returned empty for username={}, folder={}", username, albumFolder);
            return original;
        }

        SlskdDirectoryDto dir = dirs.getFirst();
        if (dir.files() == null || dir.files().isEmpty()) {
            log.warn("Directory has no files for username={}, folder={}", username, albumFolder);
            return original;
        }

        List<DownloadOption.FileItem> items = dir.files().stream()
                .map(f -> new DownloadOption.FileItem(
                        albumFolder + "\\" + f.filename(),
                        f.size(),
                        null,
                        f.bitDepth(),
                        f.sampleRate(),
                        f.length() != null ? f.length() : 0
                ))
                .toList();

        long totalSizeBytes = items.stream().mapToLong(DownloadOption.FileItem::size).sum();
        int totalSizeMB = (int) (totalSizeBytes / (1024L * 1024L));

        return new DownloadOption(
                UUID.randomUUID().toString(),
                DownloadEngine.SOULSEEK,
                original.displayName(),
                totalSizeMB,
                items,
                Map.copyOf(original.technicalMetadata())
        );
    }
}
