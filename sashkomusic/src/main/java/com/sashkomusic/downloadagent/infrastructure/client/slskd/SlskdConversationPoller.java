package com.sashkomusic.downloadagent.infrastructure.client.slskd;

import com.sashkomusic.downloadagent.infrastructure.client.slskd.dto.SlskdConversationDto;
import com.sashkomusic.events.SlskdPrivateMessageReceivedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
@Slf4j
public class SlskdConversationPoller {

    private final RestClient client;
    private final String apiKey;
    private final String ownUsername;
    private final ApplicationEventPublisher events;

    public SlskdConversationPoller(RestClient.Builder builder,
                                   @Value("${slskd.api-key:}") String apiKey,
                                   @Value("${slskd.base-url:http://localhost:5030}") String baseUrl,
                                   @Value("${slskd.soulseek-username:}") String ownUsername,
                                   ApplicationEventPublisher events) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.ownUsername = ownUsername;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void pollConversations() {
        if (apiKey == null || apiKey.isBlank()) return;

        try {
            List<SlskdConversationDto> conversations = client.get()
                    .uri("/api/v0/conversations")
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (conversations == null || conversations.isEmpty()) return;

            for (SlskdConversationDto conversation : conversations) {
                if (conversation.messages() == null) continue;

                List<SlskdConversationDto.SlskdMessageDto> unread = conversation.messages().stream()
                        .filter(m -> !m.acknowledged())
                        .filter(m -> !m.username().equalsIgnoreCase(ownUsername))
                        .toList();

                for (SlskdConversationDto.SlskdMessageDto msg : unread) {
                    acknowledgeMessage(conversation.username(), msg.id());
                    events.publishEvent(new SlskdPrivateMessageReceivedEvent(
                            conversation.username(), msg.message(), msg.timestamp()));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to poll slskd conversations: {}", e.getMessage());
        }
    }

    private void acknowledgeMessage(String username, int messageId) {
        try {
            client.put()
                    .uri("/api/v0/conversations/{username}/{id}", username, messageId)
                    .header("X-API-KEY", apiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to acknowledge slskd message id={} from {}: {}", messageId, username, e.getMessage());
        }
    }
}
