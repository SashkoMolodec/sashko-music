package com.sashkomusic.mainagent.library;

import com.sashkomusic.libraryagent.domain.entity.Marker;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.MarkerRepository;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.mainagent.bot.BotResponse;
import com.sashkomusic.mainagent.bot.ConversationContext;
import com.sashkomusic.mainagent.library.SmartlistLabelContextHolder.LabelContext;
import com.sashkomusic.mainagent.library.messaging.AddCommentTaskProducer;
import com.sashkomusic.mainagent.library.messaging.dto.AddCommentTaskDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartlistLabelFlowService {

    private static final int PAGE_SIZE = 10;
    private static final int ROW_SIZE = 5;

    private final MarkerRepository markerRepository;
    private final TrackRepository trackRepository;
    private final AddCommentTaskProducer addCommentTaskProducer;
    private final SmartlistLabelContextHolder holder;
    private final DjTagContextHolder djTagContextHolder;
    private final AlbumCommentContextHolder albumCommentContextHolder;

    public List<BotResponse> showListFromCallback(ConversationContext ctx, String data) {
        String[] p = data.split(":", 2);
        return showList(ctx, p[0], Long.parseLong(p[1]));
    }

    public List<BotResponse> showList(ConversationContext ctx, String mode, Long targetId) {
        List<Marker> all = markerRepository.findAll();
        if (all.isEmpty()) {
            return List.of(BotResponse.text("міток ще немає — створи через /labels"));
        }
        List<Long> ids = all.stream().map(Marker::getId).toList();
        holder.set(ctx.conversationId(), new LabelContext(mode, targetId, ids, 0));
        return buildPage(all, 0);
    }

    public List<BotResponse> goToPage(ConversationContext ctx, int page) {
        LabelContext lctx = holder.get(ctx.conversationId()).orElse(null);
        if (lctx == null) {
            return List.of(BotResponse.text("контекст не знайдено — тисни 🏷 знову."));
        }
        List<Marker> all = markerRepository.findAllById(lctx.markerIds());
        holder.set(ctx.conversationId(), new LabelContext(lctx.mode(), lctx.targetId(), lctx.markerIds(), page));
        return buildPage(all, page);
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

        String label = "(" + marker.getName() + ")";

        if (LabelContext.MODE_TRACK.equals(lctx.mode())) {
            djTagContextHolder.deactivateCommentMode(ctx.conversationId());
            addCommentTaskProducer.send(new AddCommentTaskDto(lctx.targetId(), label, ctx.conversationId()));
            return List.of(BotResponse.text("🏷 мітка " + label + " додається до треку"));
        } else {
            albumCommentContextHolder.clear(ctx.conversationId());
            List<Track> tracks = trackRepository.findByReleaseIdOrderByTrackNumberAsc(lctx.targetId());
            for (Track track : tracks) {
                addCommentTaskProducer.send(new AddCommentTaskDto(track.getId(), label, ctx.conversationId()));
            }
            return List.of(BotResponse.text("🏷 мітка " + label + " додається до " + tracks.size() + " треків альбому"));
        }
    }

    private List<BotResponse> buildPage(List<Marker> all, int page) {
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

        List<List<BotResponse.ButtonDto>> rows = new ArrayList<>();
        for (int i = 0; i < numButtons.size(); i += ROW_SIZE) {
            rows.add(numButtons.subList(i, Math.min(i + ROW_SIZE, numButtons.size())));
        }

        if (all.size() > PAGE_SIZE) {
            List<BotResponse.ButtonDto> nav = new ArrayList<>();
            if (page > 0) nav.add(BotResponse.ButtonDto.callback("⬅️", "LBL_PAGE:" + (page - 1)));
            if (to < all.size()) nav.add(BotResponse.ButtonDto.callback("➡️", "LBL_PAGE:" + (page + 1)));
            if (!nav.isEmpty()) rows.add(nav);
        }

        return List.of(BotResponse.withMultiRowButtons(sb.toString().stripTrailing(), rows));
    }
}
