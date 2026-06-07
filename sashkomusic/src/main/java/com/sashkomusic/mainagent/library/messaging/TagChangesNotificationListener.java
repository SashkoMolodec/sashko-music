package com.sashkomusic.mainagent.library.messaging;

import com.sashkomusic.events.TagChangesNotificationEvent;
import com.sashkomusic.libraryagent.messaging.producer.dto.TagChangesNotificationDto;
import com.sashkomusic.mainagent.bot.TelegramLogsChannel;
import com.sashkomusic.mainagent.library.NavidromeRatingSyncService;
import com.sashkomusic.mainagent.library.TagChangeNotificationFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class TagChangesNotificationListener {

    private final TelegramLogsChannel logsChannel;
    private final TagChangeNotificationFormatter formatter;
    private final NavidromeRatingSyncService ratingSync;

    @EventListener
    @Async
    public void handleTagChanges(TagChangesNotificationEvent event) {
        TagChangesNotificationDto notification = event.payload();
        logTagChanges(notification);
        logsChannel.send(formatter.format(notification));
        ratingSync.syncFromNotification(notification);
    }

    private void logTagChanges(TagChangesNotificationDto notification) {
        String tracks = notification.tracks().stream()
                .map(t -> t.artistName() + " — " + t.trackTitle() + ": " +
                        t.changes().stream()
                                .map(c -> c.tagName() + " " +
                                        (c.isNew() ? "→ " + c.newValue() : c.oldValue() + " → " + c.newValue()))
                                .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("\n  "));
        log.info("Tag updates ({} track(s)):\n  {}", notification.tracks().size(), tracks);
    }
}
