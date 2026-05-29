package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sashkomusic.libraryagent.domain.model.ReleaseMetadataFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Service for reading .release-metadata.json files from release directories.
 * Used during reprocessing to determine which releases need updating.
 */
@Service
@Slf4j
public class ReleaseMetadataReader {

    private static final String METADATA_FILENAME = ".release-metadata.json";

    private final ObjectMapper objectMapper;

    public ReleaseMetadataReader() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public Optional<ReleaseMetadataFile> readMetadata(String directoryPath) {
        Path directory = Path.of(directoryPath);
        Path metadataFile = directory.resolve(METADATA_FILENAME);

        if (!Files.exists(metadataFile)) {
            log.debug("No metadata file found at: {}", metadataFile);
            return Optional.empty();
        }

        try {
            ReleaseMetadataFile metadata = objectMapper.readValue(
                    metadataFile.toFile(),
                    ReleaseMetadataFile.class
            );
            log.debug("Successfully read metadata from: {}", metadataFile);
            return Optional.of(metadata);

        } catch (IOException e) {
            log.error("Failed to read metadata file: {}", metadataFile, e);
            return Optional.empty();
        }
    }

    public Optional<ReleaseMetadataFile> readMetadata(Path directory) {
        return readMetadata(directory.toString());
    }

    public boolean hasMetadataFile(String directoryPath) {
        Path directory = Path.of(directoryPath);
        Path metadataFile = directory.resolve(METADATA_FILENAME);
        return Files.exists(metadataFile);
    }

    public boolean hasMetadataFile(Path directory) {
        return hasMetadataFile(directory.toString());
    }
}
