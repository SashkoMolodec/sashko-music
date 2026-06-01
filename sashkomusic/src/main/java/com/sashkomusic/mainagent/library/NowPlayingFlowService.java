package com.sashkomusic.mainagent.library;

import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.config.IcecastConfig;
import com.sashkomusic.mainagent.library.DjTagContextHolder;
import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.api.service.TrackService;
import com.sashkomusic.mainagent.library.client.IcecastClient;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import com.sashkomusic.mainagent.library.messaging.RateTrackTaskProducer;
import com.sashkomusic.mainagent.library.messaging.dto.RateTrackTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NowPlayingFlowService {

    private final NavidromeClient navidromeClient;
    private final IcecastClient icecastClient;
    private final IcecastConfig icecastConfig;
    private final TrackService trackService;
    private final RateTrackTaskProducer rateTrackTaskProducer;
    private final DjTagContextHolder djTagContextHolder;

    public List<BotResponse> nowPlaying(ConversationContext ctx) {
        NavidromeClient.CurrentTrackInfo trackInfo = navidromeClient.getCurrentlyPlayingTrackInfo();

        if (trackInfo == null && icecastConfig.isEnabled()) {
            log.info("Navidrome returned null, trying Icecast fallback");
            trackInfo = icecastClient.getCurrentlyPlayingTrackInfo();
        }

        if (trackInfo == null) {
            return List.of(BotResponse.text("зараз нич не грає 🥺"));
        }

        Optional<TrackDto> track = trackService.findByArtistAndTitleOptional(trackInfo.artist(), trackInfo.title());

        TrackDto trackDto = track.orElseGet(TrackDto::empty);

        if (trackDto.id() == null) {
            return List.of(BotResponse.text("зараз грає: %s - %s, але трек не знайдено в БД".formatted(trackInfo.artist(), trackInfo.title())));
        }

        djTagContextHolder.setTrackContext(ctx.conversationId(), trackDto, trackInfo.navidromeId(), false);

        StringBuilder message = new StringBuilder();

        message.append("зараз лабанить ");

        if (trackDto.artistName() != null && !trackDto.artistName().isEmpty()) {
            message.append("_").append(trackDto.artistName()).append(" — ").append(trackDto.title()).append("_");
        } else {
            message.append("_").append(trackDto.title()).append("_");
        }

        StringBuilder emojiLine = new StringBuilder();
        if (trackDto.rating() != null) {
            String stars = convertWmpRatingToStars(trackDto.rating());
            emojiLine.append(stars);
        }
        if (trackDto.djEnergy() != null && !trackDto.djEnergy().isEmpty()) {
            emojiLine.append(" ").append(convertEnergyToEmoji(trackDto.djEnergy()));
        }
        if (trackDto.djFunction() != null && !trackDto.djFunction().isEmpty()) {
            emojiLine.append(convertFunctionToEmoji(trackDto.djFunction()));
        }

        if (emojiLine.length() > 0) {
            message.append("\n").append(emojiLine);
        }

        message.append("\n\n✏️ оціни:");

        Map<String, String> ratingButtons = createRatingButtons(trackDto.id(), trackInfo.navidromeId());
        return List.of(BotResponse.withButtons(message.toString().toLowerCase(), ratingButtons));
    }

    public List<BotResponse> handleRate(ConversationContext ctx, String data) {
        String[] parts = data.split(":");
        if (parts.length != 4) {
            return List.of(BotResponse.text("невірний формат рейтингу"));
        }

        try {
            Long trackId = Long.parseLong(parts[1]);
            int rating = Integer.parseInt(parts[2]);
            String navidromeId = parts[3];

            if (rating < 1 || rating > 5) {
                return List.of(BotResponse.text("рейтинг має бути від 1 до 5"));
            }

            navidromeClient.setRating(navidromeId, rating);

            return rateTrack(ctx, trackId, rating);
        } catch (NumberFormatException e) {
            log.error("Failed to parse rate callback: {}", data, e);
            return List.of(BotResponse.text("помилка обробки рейтингу"));
        }
    }

    private Map<String, String> createRatingButtons(Long trackId, String navidromeId) {
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("⭐ 1", "RATE:" + trackId + ":1:" + navidromeId);
        buttons.put("⭐ 2", "RATE:" + trackId + ":2:" + navidromeId);
        buttons.put("⭐ 3", "RATE:" + trackId + ":3:" + navidromeId);
        buttons.put("⭐ 4", "RATE:" + trackId + ":4:" + navidromeId);
        buttons.put("⭐ 5", "RATE:" + trackId + ":5:" + navidromeId);
        buttons.put("➕", "EXPAND_DJ_RATE:" + trackId + ":" + navidromeId);
        return buttons;
    }

    public List<BotResponse> rateTrack(ConversationContext ctx, Long trackId, int rating) {
        log.info("Rating track {} with {} stars from conversationId={}", trackId, rating, ctx.conversationId());
        RateTrackTaskDto task = new RateTrackTaskDto(trackId, rating, ctx.conversationId());
        rateTrackTaskProducer.send(task);
        return List.of();
    }

    private String convertWmpRatingToStars(String ratingStr) {
        try {
            int rating = Integer.parseInt(ratingStr);
            if (rating == 0) return "";
            if (rating <= 51) return "⭐";   // 1 star
            if (rating <= 102) return "⭐⭐";  // 2 stars
            if (rating <= 153) return "⭐⭐⭐";  // 3 stars
            if (rating <= 204) return "⭐⭐⭐⭐";  // 4 stars
            return "⭐⭐⭐⭐⭐";                      // 5 stars
        } catch (NumberFormatException e) {
            log.warn("Invalid rating format: {}", ratingStr);
            return "";
        }
    }

    private String convertEnergyToEmoji(String energy) {
        return switch (energy) {
            case "E1" -> "⚡";
            case "E2" -> "⚡⚡";
            case "E3" -> "⚡⚡⚡";
            case "E4" -> "⚡⚡⚡⚡";
            case "E5" -> "⚡⚡⚡⚡⚡";
            default -> "";
        };
    }

    private String convertFunctionToEmoji(String function) {
        return switch (function) {
            case "intro" -> "🌅";
            case "tool" -> "🔧";
            case "banger" -> "💥";
            case "closer" -> "🎆";
            default -> "";
        };
    }

    private List<BotResponse> handleIcecastTrack(NavidromeClient.CurrentTrackInfo trackInfo) {
        log.info("Handling Icecast track: {} - {}", trackInfo.artist(), trackInfo.title());

        StringBuilder message = new StringBuilder();
        message.append("зараз лабанить ");

        if (trackInfo.artist() != null && !trackInfo.artist().isEmpty() &&
                !trackInfo.artist().equalsIgnoreCase("Unknown Artist")) {
            message.append("_")
                    .append(trackInfo.artist())
                    .append(" — ")
                    .append(trackInfo.title())
                    .append("_");
        } else {
            message.append("_").append(trackInfo.title()).append("_");
        }

        message.append("\n\n🎧 live stream");

        return List.of(BotResponse.text(message.toString().toLowerCase()));
    }
}
