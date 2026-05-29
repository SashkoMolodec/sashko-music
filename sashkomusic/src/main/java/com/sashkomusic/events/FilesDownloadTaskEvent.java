package com.sashkomusic.events;

import com.sashkomusic.mainagent.download.messaging.dto.DownloadFilesTaskDto;

public record FilesDownloadTaskEvent(DownloadFilesTaskDto payload) {}
