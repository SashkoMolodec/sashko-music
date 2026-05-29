package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface FolderNameParser {

    @SystemMessage("""
            Parse music folder name to extract artist, album, and ALL additional metadata filters.

            This handles folder names like:
            - "Artist - Album"
            - "Artist - Album (Year)"
            - "Artist - Album (kaseta, 1990)"
            - "Artist - Album (vinyl, 1985-1990, україна)"
            - "[Label] Artist - Album (cd, 2000)"

            EXTRACTION RULES:
            1. Extract artist and album from main folder name (before parentheses).
            2. Extract ALL filters from text in parentheses (format, year, country, type, label, etc.).
            3. Common folder patterns:
               - "Artist - Album (filters)"
               - "Artist - Year - Album (filters)"
               - "[Label] Artist - Album (filters)"
               - "Year Artist - Album (filters)"

            AVAILABLE FILTER FIELDS (from parentheses):
            - dateRange: Year or year range.
              - Single year: "1990" -> {from: 1990, to: 1990}
              - Range: "1985-1990" -> {from: 1985, to: 1990}
              - "90s" -> {from: 1990, to: 1999}
            - format: Vinyl | CD | Cassette | Digital Media | File
              - Recognize: vinyl, вініл, платівка, грамплатівка
              - Recognize: cd, диск, компакт-диск
              - Recognize: cassette, kaseta, касета, tape, плівка
            - type: Album | EP | Single | Compilation
            - country: ISO 2-letter country code (UA, US, GB, DE, FR, JP, etc).
              - ukraine, україна -> UA; usa, америка -> US; uk, britain, англія -> GB
            - status: Official | Bootleg | Promotion
            - style: Genre/style (techno, ambient, rock, etc)
            - label: Record label name (if in parentheses)
            - catno: Catalog number

            CRITICAL RULES:
            1. Artist and album are REQUIRED — extract from folder name before parentheses.
            2. Keep exact spelling for artist/album — do NOT translate or transliterate.
            3. Filters in parentheses are OPTIONAL — extract what's present.
            4. If year appears BOTH in folder name AND parentheses, use the one from parentheses.
            5. Ignore label in square brackets [Label].
            6. Multiple filters in parentheses are comma-separated.
            7. Return empty strings for missing fields (null for dateRange).

            OUTPUT STRUCTURE (MetadataSearchRequest format):
            Return ONLY valid JSON without any markdown formatting or code blocks.
            DO NOT wrap JSON in ```json or ``` blocks.
            {
              "id": null,
              "artist": "...",
              "release": "...",
              "recording": "",
              "dateRange": {from: year, to: year} or null,
              "format": "...",
              "type": "...",
              "country": "...",
              "status": "...",
              "style": "...",
              "label": "...",
              "catno": "..."
            }

            EXAMPLES:
            "Aphex Twin - Selected Ambient Works 85-92 (1992)" →
              artist="Aphex Twin", release="Selected Ambient Works 85-92", dateRange={from:1992,to:1992}
            "Кому Вниз - Мекка (касета, 1990, україна)" →
              artist="Кому Вниз", release="Мекка", dateRange={from:1990,to:1990}, format="Cassette", country="UA"
            "Kraftwerk - Autobahn (vinyl, 1974, germany)" →
              artist="Kraftwerk", release="Autobahn", dateRange={from:1974,to:1974}, format="Vinyl", country="DE"
            "[Warp Records] Aphex Twin - Drukqs (cd, 2001)" →
              artist="Aphex Twin", release="Drukqs", dateRange={from:2001,to:2001}, format="CD"
            "Burial - Untrue" →
              artist="Burial", release="Untrue", dateRange=null
            """)
    @UserMessage("{{it}}")
    MetadataSearchRequest parse(String folderName);
}
