package com.sashkomusic.mainagent.library;

import com.sashkomusic.api.dto.TrackDto;
import com.sashkomusic.api.service.TrackService;
import com.sashkomusic.libraryagent.client.NavidromeClient;
import com.sashkomusic.libraryagent.domain.entity.Release;
import com.sashkomusic.libraryagent.domain.entity.Tag;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.repository.TrackRepository;
import com.sashkomusic.mainagent.library.client.IcecastClient;
import com.sashkomusic.mainagent.library.config.IcecastConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * What is playing right now, resolved all the way down to the library row.
 * <p>
 * The player only reports two strings ("Symphony Of Love" / "Spirit Of Love"), but everything the bot
 * can do with the playing track — DJ tagging, audio-feature similarity, "перенеси це у vault" — needs
 * its track id and release. The three steps that get there (Navidrome, Icecast fallback, DB lookup)
 * were duplicated in both `/np` flows, with the album one silently missing the Icecast fallback; they
 * live here once so the `/np` card, the `/npalbum` card and {@code LibraryAgentTools.nowPlayingTrack}
 * all answer the same question the same way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NowPlayingResolver {

    private static final int MAX_GENRES = 5;

    private final NavidromeClient navidromeClient;
    private final IcecastClient icecastClient;
    private final IcecastConfig icecastConfig;
    private final TrackService trackService;
    private final TrackRepository trackRepository;

    /**
     * @param track       the library row, or {@link TrackDto#empty()} when the player is playing
     *                    something that was never processed into the library
     * @param playerArtist artist exactly as the player reported it — the only name available when
     *                     the track is not in the library
     */
    public record NowPlaying(TrackDto track, String navidromeId,
                             String playerArtist, String playerTitle,
                             Long releaseId, String releaseTitle, Integer releaseYear,
                             List<String> genres) {

        public boolean inLibrary() {
            return track != null && track.id() != null && releaseId != null;
        }

        public String artist() {
            return track != null && track.artistName() != null && !track.artistName().isBlank()
                    ? track.artistName()
                    : playerArtist;
        }

        public String title() {
            return track != null && track.title() != null && !track.title().isBlank()
                    ? track.title()
                    : playerTitle;
        }

        /** "artist — title", or just the title when the player gave no artist. */
        public String label() {
            String artist = artist();
            return artist == null || artist.isBlank() ? title() : artist + " — " + title();
        }

        /**
         * One line an LLM can act on: the name to search for plus everything the library already
         * knows about it. Fed both to MainAgent's memory after `/np` and back as the
         * {@code nowPlayingTrack} tool result, so both paths carry identical facts.
         */
        public String summary() {
            StringBuilder sb = new StringBuilder("зараз грає: ").append(label());
            if (!inLibrary()) {
                return sb.append(" (немає в бібліотеці)").toString();
            }
            sb.append(" (реліз: ").append(releaseTitle());
            if (releaseYear != null) sb.append(", ").append(releaseYear);
            sb.append(")");
            if (genres != null && !genres.isEmpty()) {
                sb.append(", жанри: ").append(String.join(", ", genres));
            }
            if (track.stars() > 0) {
                sb.append(", рейтинг: ").append(track.stars()).append("/5");
            }
            if (track.djEnergy() != null && !track.djEnergy().isBlank()) {
                sb.append(", енергія: ").append(track.djEnergy());
            }
            if (track.djFunction() != null && !track.djFunction().isBlank()) {
                sb.append(", функція: ").append(track.djFunction());
            }
            if (track.comment() != null && !track.comment().isBlank()) {
                sb.append(", комент: ").append(track.comment());
            }
            return sb.toString();
        }
    }

    /** Empty only when nothing is playing at all — a track outside the library still resolves. */
    @Transactional(readOnly = true)
    public Optional<NowPlaying> resolve() {
        NavidromeClient.CurrentTrackInfo info = navidromeClient.getCurrentlyPlayingTrackInfo();
        if (info == null && icecastConfig.isEnabled()) {
            log.info("Navidrome returned null, trying Icecast fallback");
            info = icecastClient.getCurrentlyPlayingTrackInfo();
        }
        if (info == null) {
            return Optional.empty();
        }

        TrackDto dto = trackService.findByArtistAndTitleOptional(info.artist(), info.title())
                .orElseGet(TrackDto::empty);
        if (dto.id() == null) {
            log.info("Playing '{} - {}' is not in the library", info.artist(), info.title());
            return Optional.of(new NowPlaying(dto, info.navidromeId(), info.artist(), info.title(),
                    null, null, null, List.of()));
        }

        // Release is LAZY — read everything needed off it while this transaction is still open,
        // so the record that leaves this method is plain data.
        Release release = trackRepository.findById(dto.id()).map(Track::getRelease).orElse(null);
        if (release == null) {
            return Optional.of(new NowPlaying(dto, info.navidromeId(), info.artist(), info.title(),
                    null, null, null, List.of()));
        }
        List<String> genres = release.getTags().stream()
                .map(Tag::getName)
                .filter(name -> name != null && !name.isBlank())
                .sorted()
                .limit(MAX_GENRES)
                .toList();
        return Optional.of(new NowPlaying(dto, info.navidromeId(), info.artist(), info.title(),
                release.getId(), release.getTitle(), release.getInitialRelease(), genres));
    }
}
