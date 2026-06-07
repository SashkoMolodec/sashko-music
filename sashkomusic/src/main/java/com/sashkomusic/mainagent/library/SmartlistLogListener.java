package com.sashkomusic.mainagent.library;

import com.sashkomusic.events.SmartlistsRegeneratedEvent;
import com.sashkomusic.mainagent.bot.TelegramLogsChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmartlistLogListener {

    private final TelegramLogsChannel logsChannel;

    @EventListener
    @Async("asyncExecutor")
    public void onSmartlistsRegenerated(SmartlistsRegeneratedEvent event) {
        if (event.count() < event.total()) {
            int failed = event.total() - event.count();
            logsChannel.send("⚠️ смартлисти: " + failed + "/" + event.total() + " не оновились");
        }
    }
}
