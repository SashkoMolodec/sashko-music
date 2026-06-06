import logging
from ytmusicapi import YTMusic

logger = logging.getLogger(__name__)

_ytmusic = YTMusic()


def search(artist: str, album: str, limit: int = 5) -> list[dict]:
    query = f"{artist} {album}"
    logger.info("YouTube Music search: %s", query)
    try:
        results = _ytmusic.search(query, filter="albums", limit=limit)
        mapped = [r for item in results if (r := _map_result(item)) is not None]
        if mapped:
            logger.info("YouTube Music returned %d album results for '%s'", len(mapped), query)
            return mapped
        logger.info("No album results for '%s', falling back to song search", query)
        song_results = _ytmusic.search(query, limit=limit * 3)
        artist_lower = artist.lower()
        mapped_songs = [
            r for item in song_results
            if item.get("resultType") == "song"
            and any(a.get("name", "").lower() == artist_lower for a in (item.get("artists") or []))
            and (r := _map_song(item)) is not None
        ][:limit]
        logger.info("YouTube Music returned %d song results for '%s'", len(mapped_songs), query)
        return mapped_songs
    except Exception as e:
        logger.error("YouTube Music search failed: %s", e)
        return []


def _map_result(item: dict) -> dict | None:
    try:
        playlist_id = item.get("playlistId")
        if not playlist_id:
            return None
        title = item.get("title", "")
        artists = item.get("artists") or []
        artist_name = artists[0]["name"] if artists else item.get("artist", "")
        year = str(item.get("year") or "")
        thumbnails = item.get("thumbnails") or []
        thumbnail_url = thumbnails[-1]["url"] if thumbnails else ""
        return {
            "playlistId": playlist_id,
            "videoId": None,
            "title": title,
            "artist": artist_name,
            "year": year,
            "thumbnailUrl": thumbnail_url,
        }
    except Exception as e:
        logger.warning("Failed to map YTMusic result: %s", e)
        return None


def _map_song(item: dict) -> dict | None:
    try:
        video_id = item.get("videoId")
        if not video_id:
            return None
        title = item.get("title", "")
        artists = item.get("artists") or []
        artist_name = artists[0]["name"] if artists else ""
        year = str(item.get("year") or "")
        thumbnails = item.get("thumbnails") or []
        thumbnail_url = thumbnails[-1]["url"] if thumbnails else ""
        return {
            "playlistId": None,
            "videoId": video_id,
            "title": title,
            "artist": artist_name,
            "year": year,
            "thumbnailUrl": thumbnail_url,
        }
    except Exception as e:
        logger.warning("Failed to map YTMusic song result: %s", e)
        return None
