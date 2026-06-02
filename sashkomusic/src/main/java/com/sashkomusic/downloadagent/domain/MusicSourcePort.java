package com.sashkomusic.downloadagent.domain;

import com.sashkomusic.mainagent.download.DownloadOption;

import java.util.List;

public interface MusicSourcePort {

    List<DownloadOption> search(String artist, String release);

    String initiateDownload(DownloadOption option, String releaseId);

    String getDownloadPath(DownloadOption option);

    void handleDownloadCompletion(String conversationId, String releaseId, DownloadOption option, String downloadPath);

    void cancelDownload(String releaseId);
}
