package com.sashkomusic.mainagent.library;

import org.springframework.context.ApplicationEventPublisher;
import com.sashkomusic.events.AddMarkerTaskEvent;
import com.sashkomusic.events.RemoveMarkerTaskEvent;
import com.sashkomusic.libraryagent.domain.entity.Marker;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.MarkerRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.libraryagent.domain.service.tag.MarkerComments;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.SmartlistLabelContextHolder.LabelContext;
import com.sashkomusic.mainagent.library.SmartlistLabelContextHolder.RemovalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartlistLabelFlowService {

    private static final int PAGE_SIZE = 10;
    private static final int ROW_SIZE = 5;

    private final ApplicationEventPublisher eventPublisher;
    private final MarkerRepository markerRepository;
    private final TrackRepository trackRepository;
    private final SmartlistLabelContextHolder holder;
    private final DjTagContextHolder djTagContextHolder;
    private final AlbumCommentContextHolder albumCommentContextHolder;

    public List<BotResponse> showListFromCallback(ConversationContext ctx, String data) {
        String[] p = data.split(":", 2);
        return showList(ctx, p[0], Long.parseLong(p[1]));
    }

    @Transactional
    public List<BotResponse> showList(ConversationContext ctx, String mode, Long targetId) {
        List<Marker> all = markerRepository.findAllByOrderByLastUsedAtDescNameAsc();
        if (all.isEmpty()) {
            return List.of(BotResponse.text("міток ще немає — створи через /labels"));
        }

        abandonCommentInput(ctx, mode);

        List<Long> ids = all.stream().map(Marker::getId).toList();
        holder.set(ctx.conversationId(), new LabelContext(mode, targetId, ids, 0));
        return buildPage(all, 0, null, mode, targetId);
    }

    public List<BotResponse> showRemovableFromCallback(ConversationContext ctx, String data, Integer messageId) {
        String[] p = data.split(":", 2);
        return showRemovable(ctx, p[0], Long.parseLong(p[1]), messageId);
    }

    /** Only the markers the track (or any track of the album) carries right now — those are the ones to take off. */
    @Transactional
    public List<BotResponse> showRemovable(ConversationContext ctx, String mode, Long targetId, Integer messageId) {
        abandonCommentInput(ctx, mode);

        Set<String> names = new LinkedHashSet<>();
        for (Track track : tracksOf(mode, targetId)) {
            names.addAll(MarkerComments.markersIn(track.getTag("COMM").orElse(null)));
        }
        if (names.isEmpty()) {
            holder.clear(ctx.conversationId());
            return List.of(BotResponse.text(LabelContext.MODE_TRACK.equals(mode)
                    ? "на цьому треку міток нема"
                    : "на треках цього альбому міток нема"));
        }

        List<String> markerNames = List.copyOf(names);
        holder.setRemoval(ctx.conversationId(), new RemovalContext(mode, targetId, markerNames));

        StringBuilder sb = new StringBuilder("➖ яку мітку зняти?\n\n");
        List<BotResponse.ButtonDto> numButtons = new ArrayList<>();
        for (int i = 0; i < markerNames.size(); i++) {
            sb.append(i + 1).append(". ").append(markerNames.get(i)).append("\n");
            numButtons.add(BotResponse.ButtonDto.callback(String.valueOf(i + 1), "LBL_RM_SEL:" + i));
        }
        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>(rowsOf(numButtons));
        rows.add(List.of(BotResponse.ButtonDto.callback("❌", "LBL_CANCEL")));

        String text = sb.toString().stripTrailing();
        return List.of(messageId != null
                ? BotResponse.editCard(messageId, text, null, rows)
                : BotResponse.withMultiRowButtons(text, rows));
    }

    @Transactional
    public List<BotResponse> removeSelected(ConversationContext ctx, int index) {
        RemovalContext rctx = holder.getRemoval(ctx.conversationId()).orElse(null);
        if (rctx == null) {
            return List.of(BotResponse.text("контекст не знайдено — тисни 🏷 знову."));
        }
        if (index < 0 || index >= rctx.markerNames().size()) {
            return List.of(BotResponse.text("невірний індекс."));
        }
        holder.clear(ctx.conversationId());

        String name = rctx.markerNames().get(index);
        List<Track> tagged = tracksOf(rctx.mode(), rctx.targetId()).stream()
                .filter(track -> MarkerComments.markersIn(track.getTag("COMM").orElse(null)).contains(name))
                .toList();
        if (tagged.isEmpty()) {
            return List.of(BotResponse.text("мітки (" + name + ") тут уже нема"));
        }
        for (Track track : tagged) {
            eventPublisher.publishEvent(new RemoveMarkerTaskEvent(track.getId(), name, ctx.conversationId()));
        }
        return List.of(BotResponse.text(LabelContext.MODE_TRACK.equals(rctx.mode())
                ? "➖ мітка (" + name + ") знімається з треку"
                : "➖ мітка (" + name + ") знімається з " + tagged.size() + " треків альбому"));
    }

    private List<Track> tracksOf(String mode, Long targetId) {
        if (LabelContext.MODE_TRACK.equals(mode)) {
            return trackRepository.findById(targetId).map(List::of).orElse(List.of());
        }
        return trackRepository.findByReleaseIdOrderByTrackNumberAsc(targetId);
    }

    /**
     * Entering the label picker abandons whichever "waiting for free-text comment" mode
     * brought us here (💬 button) — otherwise that stale context stays active and the
     * next thing the user types gets misapplied as a bulk comment (same bug class as the
     * original album-comment fix, just reached via the 🏷 sub-flow instead).
     */
    private void abandonCommentInput(ConversationContext ctx, String mode) {
        if (LabelContext.MODE_TRACK.equals(mode)) {
            djTagContextHolder.deactivateCommentMode(ctx.conversationId());
        } else {
            albumCommentContextHolder.clear(ctx.conversationId());
        }
    }

    public boolean isSelecting(ConversationContext ctx) {
        return holder.get(ctx.conversationId()).isPresent();
    }

    public List<BotResponse> cancel(ConversationContext ctx) {
        holder.clear(ctx.conversationId());
        return List.of(BotResponse.text("❌ скасовано"));
    }

    public List<BotResponse> handleTypedSelection(ConversationContext ctx, String input) {
        LabelContext lctx = holder.get(ctx.conversationId()).orElse(null);
        if (lctx == null) {
            return List.of(BotResponse.text("контекст не знайдено — тисни 🏷 знову."));
        }
        int number;
        try {
            number = Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            return List.of(BotResponse.text("не зрозумів номер — введи цифру мітки або тисни ❌"));
        }
        return select(ctx, number - 1);
    }

    public List<BotResponse> goToPage(ConversationContext ctx, int page, Integer messageId) {
        LabelContext lctx = holder.get(ctx.conversationId()).orElse(null);
        if (lctx == null) {
            return List.of(BotResponse.text("контекст не знайдено — тисни 🏷 знову."));
        }
        // findAllById does not preserve input order, so re-fetching directly would render this
        // page's tag list out of sync with the order captured in markerIds() — button "N" would
        // apply whatever marker sits at index N-1 in markerIds(), not the one displayed as "N."
        // here. Re-map into markerIds()'s original order so displayed index == selection index.
        Map<Long, Marker> byId = markerRepository.findAllById(lctx.markerIds()).stream()
                .collect(Collectors.toMap(Marker::getId, m -> m));
        List<Marker> all = lctx.markerIds().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        holder.set(ctx.conversationId(), new LabelContext(lctx.mode(), lctx.targetId(), lctx.markerIds(), page));
        return buildPage(all, page, messageId, lctx.mode(), lctx.targetId());
    }

    @Transactional
    public List<BotResponse> select(ConversationContext ctx, int globalIndex) {
        LabelContext lctx = holder.get(ctx.conversationId()).orElse(null);
        if (lctx == null) {
            return List.of(BotResponse.text("контекст не знайдено — тисни 🏷 знову."));
        }
        if (globalIndex < 0 || globalIndex >= lctx.markerIds().size()) {
            return List.of(BotResponse.text("невірний індекс."));
        }
        holder.clear(ctx.conversationId());

        Marker marker = markerRepository.findById(lctx.markerIds().get(globalIndex)).orElse(null);
        if (marker == null) {
            return List.of(BotResponse.text("мітку не знайдено."));
        }
        marker.setLastUsedAt(Instant.now());

        String label = "(" + marker.getName() + ")";

        if (LabelContext.MODE_TRACK.equals(lctx.mode())) {
            djTagContextHolder.deactivateCommentMode(ctx.conversationId());
            eventPublisher.publishEvent(new AddMarkerTaskEvent(lctx.targetId(), label, ctx.conversationId()));
            return List.of(BotResponse.text("🏷 мітка " + label + " додається до треку"));
        } else {
            albumCommentContextHolder.clear(ctx.conversationId());
            List<Track> tracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(lctx.targetId());
            for (Track track : tracks) {
                eventPublisher.publishEvent(new AddMarkerTaskEvent(track.getId(), label, ctx.conversationId()));
            }
            return List.of(BotResponse.text("🏷 мітка " + label + " додається до " + tracks.size() + " треків альбому"));
        }
    }

    private List<BotResponse> buildPage(List<Marker> all, int page, Integer messageId, String mode, Long targetId) {
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, all.size());
        List<Marker> pageItems = all.subList(from, to);

        StringBuilder sb = new StringBuilder("😎 обери мітку:\n\n");
        List<BotResponse.ButtonDto> numButtons = new ArrayList<>();
        for (int i = 0; i < pageItems.size(); i++) {
            int globalIndex = from + i;
            sb.append(globalIndex + 1).append(". ").append(pageItems.get(i).getName().toLowerCase()).append("\n");
            numButtons.add(BotResponse.ButtonDto.callback(String.valueOf(globalIndex + 1), "LBL_SEL:" + globalIndex));
        }

        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>(rowsOf(numButtons));

        if (all.size() > PAGE_SIZE) {
            List<BotResponse.ButtonDto> nav = new ArrayList<>();
            if (page > 0) nav.add(BotResponse.ButtonDto.callback("⬅️", "LBL_PAGE:" + (page - 1)));
            if (to < all.size()) nav.add(BotResponse.ButtonDto.callback("➡️", "LBL_PAGE:" + (page + 1)));
            if (!nav.isEmpty()) rows.add(nav);
        }

        rows.add(List.of(
                BotResponse.ButtonDto.callback("➖ зняти", "LBL_RM_LIST:" + mode + ":" + targetId),
                BotResponse.ButtonDto.callback("❌", "LBL_CANCEL")));

        String text = sb.toString().stripTrailing();
        return List.of(messageId != null
                ? BotResponse.editCard(messageId, text, null, rows)
                : BotResponse.withMultiRowButtons(text, rows));
    }

    private static List<List<BotResponse.ButtonDto>> rowsOf(List<BotResponse.ButtonDto> buttons) {
        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += ROW_SIZE) {
            rows.add(buttons.subList(i, Math.min(i + ROW_SIZE, buttons.size())));
        }
        return rows;
    }
}
