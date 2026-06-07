package com.sashkomusic.mainagent.library;

import com.sashkomusic.libraryagent.messaging.producer.dto.TagChangesNotificationDto;
import com.sashkomusic.mainagent.library.client.NavidromeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Syncs WMP-style ratings from file tag changes to Navidrome.
 * WMP uses 0..255 scale; Navidrome uses 1..5 stars.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavidromeRatingSyncService {

    private final NavidromeClient navidromeClient;

    public void syncFromNotification(TagChangesNotificationDto notification) {
        for (TagChangesNotificationDto.TrackChanges track : notification.tracks()) {
            for (TagChangesNotificationDto.TagChangeInfo change : track.changes()) {
                if (!isRatingChange(change.tagName())) continue;
                String newValue = change.newValue();
                if (newValue == null || newValue.isEmpty()) continue;
                syncOne(track.artistName(), track.trackTitle(), newValue);
            }
        }
    }

    private void syncOne(String artist, String title, String ratingValue) {
        try {
            int wmpRating = Integer.parseInt(ratingValue);
            int stars = wmpRatingToStars(wmpRating);
            if (stars == 0) {
                log.debug("Skipping rating update for {} - {} (WMP rating is 0)", artist, title);
                return;
            }
            String navidromeId = navidromeClient.findTrackIdByArtistAndTitle(artist, title);
            if (navidromeId == null) {
                log.warn("Could not find track in Navidrome to sync rating: {} - {}", artist, title);
                return;
            }
            navidromeClient.setRating(navidromeId, stars);
            log.info("✓ Synced rating to Navidrome: {} - {} = {} stars", artist, title, stars);
        } catch (Exception e) {
            log.error("Failed to update Navidrome rating for {} - {}: {}", artist, title, e.getMessage());
        }
    }

    private static boolean isRatingChange(String tagName) {
        String upper = tagName.toUpperCase();
        return "RATING".equals(upper) || "RATING WMP".equals(upper) || "TXXX:RATING".equals(upper);
    }

    private static int wmpRatingToStars(int wmpRating) {
        if (wmpRating == 0) return 0;
        if (wmpRating <= 51) return 1;
        if (wmpRating <= 102) return 2;
        if (wmpRating <= 153) return 3;
        if (wmpRating <= 204) return 4;
        return 5;
    }
}
