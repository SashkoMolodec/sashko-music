package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.libraryagent.domain.service.processFolder.FolderNameParser;
import com.sashkomusic.mainagent.shared.model.DateRange;
import com.sashkomusic.mainagent.shared.model.Language;
import com.sashkomusic.mainagent.shared.model.MetadataSearchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseIdentifierService {

    private final FolderNameParser folderNameParser;

    private static final Pattern[] FOLDER_PATTERNS = {
            // Artist - Album (Year)
            Pattern.compile("^(?<artist>.+?)\\s*-\\s*(?<album>.+?)\\s*\\((?<year>\\d{4})\\).*$"),
            // Artist - Album [Label]
            Pattern.compile("^(?<artist>.+?)\\s*-\\s*(?<album>.+?)\\s*\\[.+?\\].*$"),
            // [Label] Artist - Album
            Pattern.compile("^\\[.+?\\]\\s*(?<artist>.+?)\\s*-\\s*(?<album>.+?)$"),
            // Year Artist - Album
            Pattern.compile("^(?<year>\\d{4})\\s+(?<artist>.+?)\\s*-\\s*(?<album>.+?)$"),
            // Artist - Album
            Pattern.compile("^(?<artist>.+?)\\s*-\\s*(?<album>.+?)$")
    };

    public record ReleaseInfo(String artist, String album, String year) {
    }

    public MetadataSearchRequest identifyFromFolderName(String folderName) {
        if (folderName == null || folderName.isEmpty()) {
            log.warn("Empty folder name provided");
            return null;
        }

        log.debug("Attempting to parse folder name: {}", folderName);
        MetadataSearchRequest aiResult = parseWithAi(folderName);
        if (aiResult != null && !aiResult.artist().isEmpty() && !aiResult.release().isEmpty()) {
            log.info("AI parsed folder name: artist='{}', release='{}', filters present: format={}, year={}, country={}",
                    aiResult.artist(), aiResult.release(),
                    !aiResult.format().isEmpty(), aiResult.dateRange() != null, !aiResult.country().isEmpty());
            return aiResult;
        }

        log.debug("AI parsing failed, trying regex patterns");
        return parseWithRegex(folderName);
    }

    private MetadataSearchRequest parseWithAi(String folderName) {
        try {
            MetadataSearchRequest result = folderNameParser.parse(folderName);

            if (result.artist().isEmpty() || result.release().isEmpty()) {
                log.warn("AI returned incomplete data: artist='{}', release='{}'",
                        result.artist(), result.release());
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("AI parsing failed: {}", e.getMessage());
            return null;
        }
    }

    private MetadataSearchRequest parseWithRegex(String folderName) {
        for (Pattern pattern : FOLDER_PATTERNS) {
            Matcher matcher = pattern.matcher(folderName.trim());
            if (matcher.matches()) {
                String artist = matcher.group("artist").trim();
                String album = matcher.group("album").trim();
                String year = null;

                try {
                    year = matcher.group("year");
                } catch (IllegalArgumentException e) {
                    // Year group doesn't exist in this pattern
                }

                log.info("Regex parsed folder name: artist='{}', album='{}', year='{}'", artist, album, year);

                // Convert to MetadataSearchRequest
                DateRange dateRange = null;
                if (year != null && !year.isEmpty()) {
                    try {
                        int yearInt = Integer.parseInt(year);
                        dateRange = new DateRange(yearInt, yearInt);
                    } catch (NumberFormatException e) {
                        log.warn("Could not parse year: {}", year);
                    }
                }

                return MetadataSearchRequest.create(
                        artist, album, null, dateRange,
                        null, null, null, null, null, null, null, Language.EN
                );
            }
        }

        log.warn("Could not parse folder name: {}", folderName);
        return null;
    }

    public ReleaseInfo identifyFromAudioFile(String audioFilePath) {
        try {
            log.debug("Attempting to read tags from audio file: {}", audioFilePath);

            AudioFile audioFile = AudioFileIO.read(new File(audioFilePath));
            Tag tag = audioFile.getTag();

            if (tag == null) {
                log.debug("No tags found in audio file");
                return null;
            }

            String artist = tag.getFirst(FieldKey.ARTIST);
            String album = tag.getFirst(FieldKey.ALBUM);
            String year = tag.getFirst(FieldKey.YEAR);

            if (artist == null || artist.trim().isEmpty() || album == null || album.trim().isEmpty()) {
                log.debug("Incomplete tags in audio file: artist='{}', album='{}'", artist, album);
                return null;
            }

            log.info("Read tags from audio file: artist='{}', album='{}', year='{}'",
                    artist.trim(), album.trim(), year);

            return new ReleaseInfo(
                    artist.trim(),
                    album.trim(),
                    year != null && !year.trim().isEmpty() ? year.trim() : null
            );

        } catch (Exception e) {
            log.debug("Could not read tags from audio file: {}", e.getMessage());
            return null;
        }
    }
}