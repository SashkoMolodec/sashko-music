package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.SmartlistsChangedEvent;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NavidromeSmartlistScanListener {

    private final NavidromeClient navidromeClient;

    @Value("${navidrome.library-path}")
    private String navidromeLibraryPath;

    @Async("asyncExecutor")
    @EventListener
    public void onSmartlistsChanged(@SuppressWarnings("unused") SmartlistsChangedEvent event) {
        String navidromePath = navidromeLibraryPath.stripTrailing() + "/smartlists";
        log.info("Triggering Navidrome scan for smartlists folder: {}", navidromePath);
        navidromeClient.triggerScan(navidromePath);
    }
}
