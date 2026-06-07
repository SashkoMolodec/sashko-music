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
        logsChannel.send("🔄 смартлисти оновлено: " + event.count() + "/" + event.total());
    }
}
