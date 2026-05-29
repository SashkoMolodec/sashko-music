package com.sashkomusic.events;

import com.sashkomusic.libraryagent.messaging.producer.dto.TagChangesNotificationDto;

public record TagChangesNotificationEvent(TagChangesNotificationDto payload) {}
