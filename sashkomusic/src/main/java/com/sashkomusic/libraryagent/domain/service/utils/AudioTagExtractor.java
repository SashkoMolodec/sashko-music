package com.sashkomusic.libraryagent.domain.service.utils;

import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTagField;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class AudioTagExtractor {

    public Map<String, String> extractAllTags(Path audioFile) {
        Map<String, String> tags = new HashMap<>();

        try {
            AudioFile audio = AudioFileIO.read(audioFile.toFile());
            Tag tag = audio.getTag();

            if (tag == null) {
                log.debug("No tags found in file: {}", audioFile);
                return tags;
            }

            extractStandardTags(tag, tags);

            if (tag instanceof AbstractID3v2Tag) {
                extractCustomTags((AbstractID3v2Tag) tag, tags);
            }

            log.debug("Extracted {} tags from: {}", tags.size(), audioFile.getFileName());

        } catch (Exception e) {
            log.error("Failed to extract tags from {}: {}", audioFile, e.getMessage());
        }

        return tags;
    }

    private void extractStandardTags(Tag tag, Map<String, String> tags) {
        addTagIfPresent(tag, FieldKey.TITLE, "TIT2", tags);
        addTagIfPresent(tag, FieldKey.ARTIST, "TPE1", tags);
        addTagIfPresent(tag, FieldKey.ALBUM, "TALB", tags);
        addTagIfPresent(tag, FieldKey.ALBUM_ARTIST, "TPE2", tags);
        addTagIfPresent(tag, FieldKey.YEAR, "TDRC", tags);
        addTagIfPresent(tag, FieldKey.GENRE, "TCON", tags);
        addTagIfPresent(tag, FieldKey.COMMENT, "COMM", tags);
        addTagIfPresent(tag, FieldKey.COMPOSER, "TCOM", tags);
        addTagIfPresent(tag, FieldKey.GROUPING, "GRP1", tags);

        addTagIfPresent(tag, FieldKey.BPM, "TBPM", tags);
        addTagIfPresent(tag, FieldKey.KEY, "TKEY", tags);

        extractInitialKey(tag, tags);
        extractRating(tag, tags);
        extractLabel(tag, tags);

        addTagIfPresent(tag, FieldKey.TRACK, "TRCK", tags);
        addTagIfPresent(tag, FieldKey.DISC_NO, "TPOS", tags);
        addTagIfPresent(tag, FieldKey.ISRC, "TSRC", tags);
        addTagIfPresent(tag, FieldKey.MUSICBRAINZ_TRACK_ID, "UFID", tags);
    }

    private void extractCustomTags(AbstractID3v2Tag id3Tag, Map<String, String> tags) {
        try {
            var fields = id3Tag.getFields();
            while (fields.hasNext()) {
                var field = fields.next();

                if (field instanceof AbstractID3v2Frame) {
                    AbstractID3v2Frame frame = (AbstractID3v2Frame) field;

                    // Check if it's a TXXX frame (user-defined text)
                    if ("TXXX".equals(frame.getId()) && frame.getBody() instanceof FrameBodyTXXX) {
                        FrameBodyTXXX body = (FrameBodyTXXX) frame.getBody();
                        String description = body.getDescription();
                        String value = body.getText();

                        if (description != null && !description.isEmpty() && value != null && !value.isEmpty()) {
                            tags.put("TXXX:" + description.toUpperCase(), value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract custom TXXX frames: {}", e.getMessage());
        }
    }

    private void addTagIfPresent(Tag tag, FieldKey fieldKey, String frameName, Map<String, String> tags) {
        try {
            String value = tag.getFirst(fieldKey);
            if (value != null && !value.isEmpty()) {
                tags.put(frameName, value);
            }
        } catch (Exception e) {
            log.trace("Failed to extract {}: {}", frameName, e.getMessage());
        }
    }

    /**
     * Extracts INITIALKEY tag (Traktor Pro key notation like "1m" for D minor)
     */
    private void extractInitialKey(Tag tag, Map<String, String> tags) {
        try {
            String initialKey = tag.getFirst("INITIALKEY");
            if (initialKey != null && !initialKey.isEmpty()) {
                tags.put("INITIALKEY", initialKey);
            }
        } catch (Exception e) {
            log.trace("Failed to extract INITIALKEY: {}", e.getMessage());
        }
    }

    private void extractRating(Tag tag, Map<String, String> tags) {
        try {
            String rating = tag.getFirst(FieldKey.RATING);
            if (rating != null && !rating.isEmpty()) {
                tags.put("RATING", rating);
            }
        } catch (Exception e) {
            log.trace("Failed to extract RATING: {}", e.getMessage());
        }

        try {
            String ratingWmp = tag.getFirst("RATING WMP");
            if (ratingWmp != null && !ratingWmp.isEmpty()) {
                tags.put("RATING WMP", ratingWmp);
            }
        } catch (Exception e) {
            log.trace("Failed to extract RATING WMP: {}", e.getMessage());
        }
    }

    private void extractLabel(Tag tag, Map<String, String> tags) {
        String label = null;
        try {
            label = tag.getFirst("ORGANIZATION");
        } catch (Exception e) {
            log.trace("Failed to extract ORGANIZATION: {}", e.getMessage());
        }
        if (label == null || label.isEmpty()) {
            label = tag.getFirst(FieldKey.RECORD_LABEL);
        }
        if (label != null && !label.isEmpty()) {
            tags.put("PUBLISHER", label);
        }
    }

    public Map<String, String> extractSpecificTags(Path audioFile, String... tagNames) {
        Map<String, String> allTags = extractAllTags(audioFile);
        Map<String, String> filteredTags = new HashMap<>();

        for (String tagName : tagNames) {
            if (allTags.containsKey(tagName)) {
                filteredTags.put(tagName, allTags.get(tagName));
            }
        }

        return filteredTags;
    }

    /**
     * Writes rating to audio file in both RATING and RATING WMP formats
     * @param audioFile Path to audio file
     * @param rating Rating value (1-5 stars)
     * @return true if successful
     */
    public boolean writeRating(Path audioFile, int rating) {
        if (rating < 0 || rating > 5) {
            log.error("Invalid rating: {}. Must be 0-5", rating);
            return false;
        }

        try {
            AudioFile audio = AudioFileIO.read(audioFile.toFile());
            Tag tag = audio.getTagOrCreateAndSetDefault();

            // Convert stars to Traktor WMP format: 1→51, 2→102, 3→153, 4→204, 5→255
            int ratingWmp = convertStarsToWmpRating(rating);
            String ratingWmpStr = String.valueOf(ratingWmp);

            // Set standard RATING field (for Navidrome)
            tag.setField(FieldKey.RATING, ratingWmpStr);

            // Set RATING WMP field (Traktor-compatible)
            if (tag instanceof FlacTag || tag instanceof VorbisCommentTag) {
                // For FLAC/OGG files - use Vorbis Comment
                try {
                    // Create custom RATING WMP field
                    VorbisCommentTagField ratingWmpField = new VorbisCommentTagField("RATING WMP", ratingWmpStr);

                    // For FlacTag and VorbisCommentTag, setField should work
                    tag.setField(ratingWmpField);
                    log.info("Set RATING WMP Vorbis comment to: {}", ratingWmpStr);
                } catch (Exception e) {
                    log.error("Could not set RATING WMP Vorbis comment: {}", e.getMessage(), e);
                }
            } else if (tag instanceof AbstractID3v2Tag) {
                // For MP3 files - use TXXX frame
                try {
                    AbstractID3v2Tag id3Tag = (AbstractID3v2Tag) tag;

                    // Remove existing RATING WMP TXXX frame if present
                    var fields = id3Tag.getFields("TXXX");
                    for (var field : fields) {
                        if (field instanceof AbstractID3v2Frame) {
                            AbstractID3v2Frame id3Frame = (AbstractID3v2Frame) field;
                            if (id3Frame.getBody() instanceof FrameBodyTXXX) {
                                FrameBodyTXXX body = (FrameBodyTXXX) id3Frame.getBody();
                                if ("RATING WMP".equalsIgnoreCase(body.getDescription())) {
                                    try {
                                        id3Tag.deleteField(field.getId());
                                        log.trace("Removed old RATING WMP TXXX frame");
                                    } catch (Exception e) {
                                        log.trace("Failed to remove old RATING WMP frame: {}", e.getMessage());
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    // Create new TXXX frame for RATING WMP
                    FrameBodyTXXX frameBody = new FrameBodyTXXX();
                    frameBody.setDescription("RATING WMP");
                    frameBody.setText(ratingWmpStr);

                    ID3v24Frame frame = new ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO);
                    frame.setBody(frameBody);

                    id3Tag.addField(frame);
                    log.trace("Added RATING WMP TXXX frame with value: {}", ratingWmpStr);
                } catch (Exception e) {
                    log.warn("Could not set RATING WMP TXXX frame: {}", e.getMessage());
                }
            } else {
                log.debug("Unknown tag type {}, only RATING field will be set", tag.getClass().getName());
            }

            audio.commit();
            log.info("Wrote rating {} (WMP: {}) to RATING and RATING WMP: {}", rating, ratingWmp, audioFile.getFileName());
            return true;

        } catch (Exception e) {
            log.error("Failed to write rating to {}: {}", audioFile, e.getMessage(), e);
            return false;
        }
    }

    private int convertStarsToWmpRating(int stars) {
        return switch (stars) {
            case 0 -> 0;
            case 1 -> 51;
            case 2 -> 102;
            case 3 -> 153;
            case 4 -> 204;
            case 5 -> 255;
            default -> throw new IllegalArgumentException("Invalid rating: " + stars);
        };
    }
}
