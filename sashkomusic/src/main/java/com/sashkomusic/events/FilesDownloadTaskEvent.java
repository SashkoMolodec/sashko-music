package com.sashkomusic.events;

import com.sashkomusic.shared.task.DownloadFilesTask;

public record FilesDownloadTaskEvent(DownloadFilesTask payload) {}
