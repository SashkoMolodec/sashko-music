package com.sashkomusic.mainagent.library;

import org.springframework.context.ApplicationEventPublisher;
import com.sashkomusic.events.RateTrackTaskEvent;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.DjTagContextHolder;
import com.sashkomusic.mainagent.library.NowPlayingResolver.NowPlaying;
import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.libraryagent.client.NavidromeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NowPlayingFlowService {

    private final ApplicationEventPublisher eventPublisher;
    private final NavidromeClient navidromeClient;
    private final NowPlayingResolver nowPlayingResolver;
    private final DjTagContextHolder djTagContextHolder;
    private final LastReleaseContextHolder lastReleaseContextHolder;

    /**
     * The card the user sees plus the one line MainAgent's memory gets, so a follow-up like
     * "схоже до того що зараз грає?" has the track name in context instead of asking the user
     * what their own player is doing.
     *
     * @param agentContext blank when there is nothing worth telling the agent
     */
    public record NowPlayingResult(List<BotResponse> responses, String agentContext) {
        static NowPlayingResult of(String text) {
            return new NowPlayingResult(List.of(BotResponse.text(text)), "");
        }
    }

    public NowPlayingResult nowPlaying(ConversationContext ctx) {
        Optional<NowPlaying> resolved = nowPlayingResolver.resolve();
        if (resolved.isEmpty()) {
            return NowPlayingResult.of("зараз нич не грає 🥺");
        }
        NowPlaying playing = resolved.get();

        if (!playing.inLibrary()) {
            return new NowPlayingResult(
                    List.of(BotResponse.text("зараз грає: %s - %s, але трек не знайдено в БД"
                            .formatted(playing.playerArtist(), playing.playerTitle()))),
                    playing.summary());
        }

        TrackDto trackDto = playing.track();
        djTagContextHolder.setTrackContext(ctx.conversationId(), trackDto, playing.navidromeId(), false);
        // The playing release becomes the "this" referent — "перенеси це у vault" / "маю схоже?"
        // right after /np must not need the release named again.
        lastReleaseContextHolder.set(ctx.conversationId(), playing.releaseId(),
                playing.releaseTitle(), trackDto.artistName());

        StringBuilder message = new StringBuilder();

        if (trackDto.artistName() != null && !trackDto.artistName().isEmpty()) {
            message.append(trackDto.artistName()).append(" — ").append(trackDto.title());
        } else {
            message.append(trackDto.title());
        }

        StringBuilder emojiLine = new StringBuilder();
        emojiLine.append("⭐".repeat(trackDto.stars()));
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

        List<List<BotResponse.ButtonDto>> rows = buildDjPanelRows(trackDto.id(), playing.navidromeId());
        return new NowPlayingResult(
                List.of(BotResponse.withMultiRowButtons(message.toString().toLowerCase(), rows)),
                playing.summary());
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
        eventPublisher.publishEvent(new RateTrackTaskEvent(trackId, rating, ctx.conversationId()));
        return List.of();
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
}
