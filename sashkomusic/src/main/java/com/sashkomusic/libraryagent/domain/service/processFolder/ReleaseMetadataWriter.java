package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sashkomusic.mainagent.shared.model.ReleaseMetadata;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@Slf4j
public class ReleaseMetadataWriter {

    private static final String METADATA_FILENAME = ".release-metadata.json";

    private final ObjectMapper objectMapper;

    public ReleaseMetadataWriter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void writeMetadata(String directoryPath, ReleaseMetadata metadata, int version) {
        Path directory = Path.of(directoryPath);
        Path metadataFile = directory.resolve(METADATA_FILENAME);
        Path tempFile = directory.resolve(METADATA_FILENAME + ".tmp");

        try {
            ReleaseMetadataFile file = ReleaseMetadataFile.from(metadata, version);

            objectMapper.writeValue(tempFile.toFile(), file);

            Files.move(tempFile, metadataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            log.info("Successfully wrote metadata file: {}", metadataFile);

        } catch (IOException e) {
            log.error("Failed to write metadata file: {}", metadataFile, e);
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupError) {
                log.warn("Failed to cleanup temp file: {}", tempFile, cleanupError);
            }

            throw new RuntimeException("Failed to write release metadata file", e);
        }
    }
}
