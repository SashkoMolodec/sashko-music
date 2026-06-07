package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.SmartlistsChangedEvent;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NavidromeSmartlistScanListener {

    private static final String SMARTLISTS_RELATIVE_PATH = "smartlists";

    private final NavidromeClient navidromeClient;

    @Async("asyncExecutor")
    @EventListener
    public void onSmartlistsChanged(@SuppressWarnings("unused") SmartlistsChangedEvent event) {
        log.info("Triggering Navidrome scan for smartlists folder");
        navidromeClient.triggerScan(SMARTLISTS_RELATIVE_PATH);
    }
}
