package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.sashkomusic.libraryagent.domain.model.ValidationResult;
import com.sashkomusic.mainagent.process.messaging.dto.ProcessLibraryTaskDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class FileValidator {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "mp3", "flac", "m4a", "ogg", "wav", "opus", "aac"
    );

    public ValidationResult validate(ProcessLibraryTaskDto task) {
        List<String> errors = new ArrayList<>();

        Path directory = Paths.get(task.directoryPath());
        if (!Files.exists(directory)) {
            errors.add("Directory does not exist: " + task.directoryPath());
            return ValidationResult.invalid(errors);
        }

        if (!Files.isDirectory(directory)) {
            errors.add("Path is not a directory: " + task.directoryPath());
            return ValidationResult.invalid(errors);
        }

        if (task.downloadedFiles() == null || task.downloadedFiles().isEmpty()) {
            errors.add("No files provided for processing");
            return ValidationResult.invalid(errors);
        }

        log.info("Validating {} files from task", task.downloadedFiles().size());
        task.downloadedFiles().forEach(f -> log.debug("File in task: {}", f));

        int audioFileCount = 0;
        for (String filePath : task.downloadedFiles()) {
            Path file = Paths.get(filePath);
            log.info("Checking if file exists: {}", file.toAbsolutePath());

            if (!Files.exists(file)) {
                errors.add("File does not exist: " + filePath);
                log.error("File not found: {}", file.toAbsolutePath());
                continue;
            }

            if (!Files.isRegularFile(file)) {
                log.warn("Skipping non-file: {}", filePath);
                continue;
            }

            if (!isAudioFile(file)) {
                log.warn("Skipping non-audio file: {}", filePath);
                continue;
            }

            audioFileCount++;
        }

        if (audioFileCount == 0) {
            errors.add("No valid audio files found in the provided list");
        }

        if (task.metadata() == null) {
            errors.add("No release metadata provided");
        }

        if (errors.isEmpty()) {
            log.info("Validation passed: {} audio files ready for processing", audioFileCount);
            return ValidationResult.valid();
        } else {
            log.warn("Validation failed with {} errors", errors.size());
            return ValidationResult.invalid(errors);
        }
    }

    private boolean isAudioFile(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        return SUPPORTED_EXTENSIONS.stream()
                .anyMatch(ext -> filename.endsWith("." + ext));
    }
}