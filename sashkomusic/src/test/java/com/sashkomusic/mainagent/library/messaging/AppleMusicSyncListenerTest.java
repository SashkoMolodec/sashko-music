package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.ReleasePlacedEvent;
import com.sashkomusic.mainagent.library.AppleMusicSyncService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AppleMusicSyncListenerTest {

    private final AppleMusicSyncService syncService = mock(AppleMusicSyncService.class);
    private final AppleMusicSyncListener listener = new AppleMusicSyncListener(syncService);

    @Test
    void the_sync_starts_from_the_final_path_once_the_release_is_placed() {
        String path = "/mnt/media_hdd/lib/vault/арніка/естрадний ансамбль арніка (1974) [flac]";

        listener.handleReleasePlaced(new ReleasePlacedEvent("-100:5", 173L, "Арніка", path));

        verify(syncService).submit("-100:5", path, "естрадний ансамбль арніка (1974) [flac]");
    }

    @Test
    void an_event_without_a_path_syncs_nothing() {
        listener.handleReleasePlaced(new ReleasePlacedEvent("-100:5", 173L, "Арніка", ""));

        verify(syncService, never()).submit(anyString(), anyString(), anyString());
    }
}
