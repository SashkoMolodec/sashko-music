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

        if (trackDto.artistName() != null && !trackDto.artistName().isEmpty()) {
            message.append(trackDto.artistName()).append(" — ").append(trackDto.title());
        } else {
            message.append(trackDto.title());
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

        if (trackDto.comment() != null && !trackDto.comment().isEmpty()) {
            message.append("\n💬 ").append(trackDto.comment());
        }

        message.append("\n\n✏️ оціни:");

        List<List<BotResponse.ButtonDto>> rows = buildDjPanelRows(trackDto.id(), trackInfo.navidromeId());
        return List.of(BotResponse.withMultiRowButtons(message.toString().toLowerCase(), rows));
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

    private List<List<BotResponse.ButtonDto>> buildDjPanelRows(Long trackId, String navidromeId) {
        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>();

        List<BotResponse.ButtonDto> stars = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            stars.add(BotResponse.ButtonDto.callback("⭐ " + i, "RATE:" + trackId + ":" + i + ":" + navidromeId));
        }
        rows.add(stars);

        List<BotResponse.ButtonDto> energy = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            energy.add(BotResponse.ButtonDto.callback("⚡ " + i, "ENERGY_RATE:" + trackId + ":E" + i + ":" + navidromeId));
        }
        rows.add(energy);

        List<BotResponse.ButtonDto> function = new ArrayList<>();
        function.add(BotResponse.ButtonDto.callback("🌅", "FUNCTION_RATE:" + trackId + ":intro:" + navidromeId));
        function.add(BotResponse.ButtonDto.callback("🔧", "FUNCTION_RATE:" + trackId + ":tool:" + navidromeId));
        function.add(BotResponse.ButtonDto.callback("💥", "FUNCTION_RATE:" + trackId + ":banger:" + navidromeId));
        function.add(BotResponse.ButtonDto.callback("🎆", "FUNCTION_RATE:" + trackId + ":closer:" + navidromeId));
        function.add(BotResponse.ButtonDto.callback("💬", "ADD_COMMENT:" + trackId + ":" + navidromeId));
        rows.add(function);

        return rows;
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
        if (trackInfo.artist() != null && !trackInfo.artist().isEmpty() &&
                !trackInfo.artist().equalsIgnoreCase("Unknown Artist")) {
            message.append(trackInfo.artist()).append(" — ").append(trackInfo.title());
        } else {
            message.append(trackInfo.title());
        }

        message.append("\n\n🎧 live stream");

        return List.of(BotResponse.text(message.toString().toLowerCase()));
    }
}
