package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.SmartlistDeletedEvent;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NavidromePlaylistDeleteListener {

    private final NavidromeClient navidromeClient;

    @Async("asyncExecutor")
    @EventListener
    public void onSmartlistDeleted(SmartlistDeletedEvent event) {
        String name = event.name();
        log.info("Deleting Navidrome playlist for smartlist '{}'", name);
        String playlistId = navidromeClient.findPlaylistIdByName(name);
        if (playlistId == null) {
            log.warn("Navidrome playlist '{}' not found — nothing to delete", name);
            return;
        }
        navidromeClient.deletePlaylist(playlistId);
    }
}
