package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.mainagent.search.SearchEngine;
import com.sashkomusic.libraryagent.domain.model.TrackMatch;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;

@Slf4j
@Service
public class AudioTagger {

    public void tagFile(Path audioFile, ReleaseMetadata metadata, TrackMatch match, byte[] coverArt) {
        try {
            log.info("Starting tag operation for: {}", audioFile.getFileName());

            AudioFile f = AudioFileIO.read(audioFile.toFile());
            Tag tag = f.getTagOrCreateAndSetDefault();

            String existingKey = tag.getFirst(FieldKey.KEY);
            String existingBpm = tag.getFirst(FieldKey.BPM);

            log.debug("Writing tags: album_artist='{}', artist='{}', album='{}', track={} - '{}'",
                    metadata.artist(), match.artist(), metadata.title(), match.trackNumber(), match.trackTitle());
            if (existingKey != null && !existingKey.isEmpty()) {
                log.debug("Preserving existing KEY: {}", existingKey);
            }
            if (existingBpm != null && !existingBpm.isEmpty()) {
                log.debug("Preserving existing BPM: {}", existingBpm);
            }

            // Use per-track artist for ARTIST tag
            tag.setField(FieldKey.ARTIST, match.artist());
            // Use album artist for ALBUM_ARTIST tag
            tag.setField(FieldKey.ALBUM_ARTIST, metadata.artist());
            tag.setField(FieldKey.ALBUM, metadata.title());
            tag.setField(FieldKey.TITLE, match.trackTitle());
            tag.setField(FieldKey.TRACK, String.valueOf(match.trackNumber()));

            if (metadata.years() != null && !metadata.years().isEmpty()) {
                tag.setField(FieldKey.YEAR, metadata.years().getFirst());
            }

            if (metadata.tags() != null && !metadata.tags().isEmpty()) {
                String allGenres = String.join(";", metadata.tags());
                tag.setField(FieldKey.GENRE, allGenres);
            }

            if (metadata.types() != null && !metadata.types().isEmpty()) {
                String allTypes = String.join(";", metadata.types());
                tag.setField(FieldKey.GROUPING, allTypes);
            }

            if (metadata.label() != null && !metadata.label().isEmpty()) {
                setLabelTag(tag, audioFile, metadata.label());
            }

            if (metadata.tags() != null && !metadata.tags().isEmpty()) {
                String tagsComment = String.join(", ", metadata.tags());
                tag.setField(FieldKey.COMMENT, tagsComment);
            }

            if (tag instanceof AbstractID3v2Tag id3Tag) {
                if (metadata.id() != null && !metadata.id().isEmpty()) {
                    addCustomTextField(id3Tag, "RELEASEID", metadata.id());
                }

                SearchEngine source = metadata.source();
                if (source != null) {
                    addCustomTextField(id3Tag, "SOURCE", source.name());
                }

                if (metadata.years() != null && metadata.years().size() > 1) {
                    String allYears = String.join(";", metadata.years());
                    addCustomTextField(id3Tag, "RELEASEYEARS", allYears);
                }
            }

            if (coverArt != null && coverArt.length > 0) {
                try {
                    Artwork artwork = ArtworkFactory.createArtworkFromFile(new File(audioFile.getParent().toString(), "cover.jpg"));
                    tag.deleteArtworkField();
                    tag.setField(artwork);
                } catch (Exception ex) {
                    log.warn("Failed to embed cover art in {}: {}", audioFile.getFileName(), ex.getMessage());
                }
            }

            if (existingKey != null && !existingKey.isEmpty()) {
                tag.setField(FieldKey.KEY, existingKey);
                log.debug("Restored KEY: {}", existingKey);
            }
            if (existingBpm != null && !existingBpm.isEmpty()) {
                tag.setField(FieldKey.BPM, existingBpm);
                log.debug("Restored BPM: {}", existingBpm);
            }

            f.commit();

            log.info("Successfully tagged and saved: {} - {}", match.trackNumber(), match.trackTitle());

        } catch (Exception ex) {
            log.error("Error tagging file {}: {}", audioFile.getFileName(), ex.getMessage(), ex);
            throw new TaggingException("Failed to tag file: " + audioFile.getFileName(), ex);
        }
    }

    public static class TaggingException extends RuntimeException {
        public TaggingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void addCustomTextField(AbstractID3v2Tag tag, String description, String value) {
        try {
            FrameBodyTXXX frameBody = new FrameBodyTXXX();
            frameBody.setDescription(description);
            frameBody.setText(value);

            ID3v24Frame frame = new ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO);
            frame.setBody(frameBody);

            tag.setFrame(frame);
            log.debug("Added custom field: {}={}", description, value);
        } catch (Exception e) {
            log.warn("Failed to add custom field {}: {}", description, e.getMessage());
        }
    }

    /**
     * Sets the label/publisher tag in a format-appropriate way.
     * For FLAC files: writes ORGANIZATION tag (Traktor-compatible) and removes LABEL
     * For MP3 files: writes TPUB frame via RECORD_LABEL
     * For M4A: writes to publisher atom
     */
    private void setLabelTag(Tag tag, Path audioFile, String label) {
        try {
            String filename = audioFile.getFileName().toString().toLowerCase();
            if (filename.endsWith(".flac") || filename.endsWith(".ogg")) {
                // FLAC uses FlacTag which wraps VorbisCommentTag
                VorbisCommentTag vorbisTag = null;

                if (tag instanceof FlacTag flacTag) {
                    vorbisTag = flacTag.getVorbisCommentTag();
                } else if (tag instanceof VorbisCommentTag) {
                    vorbisTag = (VorbisCommentTag) tag;
                }

                if (vorbisTag != null) {
                    // Delete both LABEL and ORGANIZATION to ensure clean state
                    try {
                        vorbisTag.deleteField("LABEL");
                        log.debug("Deleted old LABEL field");
                    } catch (Exception e) {
                        log.trace("No LABEL field to delete");
                    }
                    try {
                        vorbisTag.deleteField("ORGANIZATION");
                        log.trace("Deleted old ORGANIZATION field");
                    } catch (Exception e) {
                        log.trace("No ORGANIZATION field to delete");
                    }

                    // Set new ORGANIZATION
                    vorbisTag.setField("ORGANIZATION", label);
                    log.debug("Set ORGANIZATION (Vorbis): {}", label);
                } else {
                    // Fallback (shouldn't happen)
                    log.warn("FLAC file but cannot get VorbisCommentTag, using RECORD_LABEL fallback");
                    tag.setField(FieldKey.RECORD_LABEL, label);
                }
            } else {
                // MP3, M4A and other formats - RECORD_LABEL maps to TPUB/publisher
                tag.setField(FieldKey.RECORD_LABEL, label);
                log.debug("Set RECORD_LABEL ({}): {}", filename.substring(filename.lastIndexOf('.') + 1), label);
            }
        } catch (Exception e) {
            log.warn("Failed to set label for {}: {}", audioFile.getFileName(), e.getMessage());
        }
    }

    public TrackInfo readTrackInfo(Path audioFile) {
        try {
            AudioFile f = AudioFileIO.read(audioFile.toFile());
            Tag tag = f.getTag();

            if (tag == null) {
                log.warn("No tags found in: {}", audioFile.getFileName());
                return null;
            }

            String trackNumber = tag.getFirst(FieldKey.TRACK);
            String title = tag.getFirst(FieldKey.TITLE);
            String artist = tag.getFirst(FieldKey.ARTIST);

            Integer trackNum = parseTrackNumber(trackNumber);

            if (trackNum == null || title == null || title.isEmpty()) {
                log.warn("Incomplete track info in {}: track={}, title={}",
                    audioFile.getFileName(), trackNumber, title);
                return null;
            }

            return new TrackInfo(trackNum, title, artist != null ? artist : "");

        } catch (Exception ex) {
            log.error("Failed to read track info from {}: {}", audioFile.getFileName(), ex.getMessage());
            return null;
        }
    }

    private Integer parseTrackNumber(String trackNumberStr) {
        if (trackNumberStr == null || trackNumberStr.isEmpty()) {
            return null;
        }

        String[] parts = trackNumberStr.split("[/\\\\]");
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record TrackInfo(int trackNumber, String title, String artist) {}
}
