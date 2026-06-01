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
        logger.info("YouTube Music returned %d results for '%s'", len(mapped), query)
        return mapped
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
            "title": title,
            "artist": artist_name,
            "year": year,
            "thumbnailUrl": thumbnail_url,
        }
    except Exception as e:
        logger.warning("Failed to map YTMusic result: %s", e)
        return None
