package com.sashkomusic.libraryagent.domain.service.tag;

import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.ID3v24Frames;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTagField;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@Slf4j
public class DjTagWriter {

    public boolean writeDjEnergy(Path audioFile, String energyLevel) {
        return writeCustomTag(audioFile, "DJ_ENERGY", energyLevel);
    }

    public boolean writeDjFunction(Path audioFile, String functionType) {
        return writeCustomTag(audioFile, "DJ_FUNCTION", functionType);
    }

    public boolean prependEnergy(Path audioFile, String energy) {
        return prependToComment(audioFile, energy, "energy");
    }

    public boolean prependFunction(Path audioFile, String function) {
        return prependToComment(audioFile, function, "function");
    }

    public boolean prependCommentText(Path audioFile, String commentText) {
        return prependToComment(audioFile, commentText, "comment");
    }

    private boolean prependToComment(Path audioFile, String value, String label) {
        try {
            AudioFile audio = AudioFileIO.read(audioFile.toFile());
            Tag tag = audio.getTagOrCreateAndSetDefault();

            String existingComment = tag.getFirst(FieldKey.COMMENT);
            String combinedComment;
            if (existingComment != null && !existingComment.isEmpty()) {
                combinedComment = value + "; " + existingComment;
            } else {
                combinedComment = value;
            }

            tag.setField(FieldKey.COMMENT, combinedComment);
            audio.commit();
            log.info("Prepended {} to COMM tag in {}: {}", label, audioFile.getFileName(), value);
            return true;
        } catch (Exception e) {
            log.error("Failed to prepend {} to COMM tag in {}: {}", label, audioFile, e.getMessage(), e);
            return false;
        }
    }

    private boolean writeCustomTag(Path audioFile, String tagName, String tagValue) {
        try {
            AudioFile audio = AudioFileIO.read(audioFile.toFile());
            Tag tag = audio.getTagOrCreateAndSetDefault();

            if (tag instanceof FlacTag || tag instanceof VorbisCommentTag) {
                // FLAC/OGG - Vorbis Comment
                try {
                    VorbisCommentTagField field = new VorbisCommentTagField(tagName, tagValue);
                    tag.setField(field);
                    log.info("Set {} Vorbis comment to: {} for {}", tagName, tagValue, audioFile.getFileName());
                } catch (Exception e) {
                    log.error("Could not set {} Vorbis ADD_COMMENT: {}", tagName, e.getMessage(), e);
                    return false;
                }
            } else if (tag instanceof AbstractID3v2Tag) {
                // MP3 - TXXX frame
                try {
                    AbstractID3v2Tag id3Tag = (AbstractID3v2Tag) tag;
                    removeExistingTxxxFrame(id3Tag, tagName);

                    FrameBodyTXXX frameBody = new FrameBodyTXXX();
                    frameBody.setDescription(tagName);
                    frameBody.setText(tagValue);

                    ID3v24Frame frame = new ID3v24Frame(ID3v24Frames.FRAME_ID_USER_DEFINED_INFO);
                    frame.setBody(frameBody);

                    id3Tag.addField(frame);
                    log.info("Set {} TXXX frame to: {} for {}", tagName, tagValue, audioFile.getFileName());
                } catch (Exception e) {
                    log.error("Could not set {} TXXX frame: {}", tagName, e.getMessage(), e);
                    return false;
                }
            } else {
                log.warn("Unknown tag type {} for file {}", tag.getClass().getName(), audioFile.getFileName());
                return false;
            }

            audio.commit();
            return true;
        } catch (Exception e) {
            log.error("Failed to write {} tag to {}: {}", tagName, audioFile, e.getMessage(), e);
            return false;
        }
    }

    private void removeExistingTxxxFrame(AbstractID3v2Tag id3Tag, String description) {
        try {
            var fields = id3Tag.getFields("TXXX");
            for (var field : fields) {
                if (field instanceof AbstractID3v2Frame) {
                    AbstractID3v2Frame id3Frame = (AbstractID3v2Frame) field;
                    if (id3Frame.getBody() instanceof FrameBodyTXXX) {
                        FrameBodyTXXX body = (FrameBodyTXXX) id3Frame.getBody();
                        if (description.equalsIgnoreCase(body.getDescription())) {
                            try {
                                id3Tag.deleteField(field.getId());
                                log.trace("Removed old {} TXXX frame", description);
                            } catch (Exception e) {
                                log.trace("Failed to remove old {} frame: {}", description, e.getMessage());
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to remove existing {} TXXX frame: {}", description, e.getMessage());
        }
    }
}
