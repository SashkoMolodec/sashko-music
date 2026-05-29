package com.sashkomusic.libraryagent.domain.service.processFolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sashkomusic.libraryagent.domain.entity.Track;
import com.sashkomusic.libraryagent.domain.entity.TrackAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;

@Service
@Slf4j
@RequiredArgsConstructor
public class TrackAnalysisJsonReader {

    private final ObjectMapper objectMapper;

    public TrackAnalysis readAnalysisFromJson(String jsonFilePath, Track track) throws IOException {
        log.info("Reading analysis JSON from: {} (original: {})", jsonFilePath, jsonFilePath);

        File jsonFile = new File(jsonFilePath);
        if (!jsonFile.exists()) {
            throw new IOException("Analysis JSON file not found: " + jsonFilePath);
        }

        JsonNode root = objectMapper.readTree(jsonFile);

        TrackAnalysis analysis = new TrackAnalysis(track);

        // Check if analysis was successful
        boolean success = root.path("success").asBoolean(false);
        String errorMessage = root.path("errorMessage").asText(null);

        if (!success || errorMessage != null) {
            analysis.setErrorMessage(errorMessage != null ? errorMessage : "Unknown error");
            log.warn("Analysis failed for track {}: {}", track.getId(), analysis.getErrorMessage());
            return analysis;
        }

        // Extract features
        JsonNode features = root.path("features");

        if (features.isMissingNode()) {
            analysis.setErrorMessage("Missing features in JSON");
            return analysis;
        }

        try {
            // Rhythmic features
            JsonNode rhythmic = features.path("rhythmic");
            analysis.setBpm(getBigDecimal(rhythmic, "bpm"));
            analysis.setDanceability(getBigDecimal(rhythmic, "danceability"));
            analysis.setBeatsLoudness(getBigDecimal(rhythmic, "beats_loudness"));
            analysis.setOnsetRate(getBigDecimal(rhythmic, "onset_rate"));

            // Timbral features
            JsonNode timbral = features.path("timbral");

            // MFCC features
            analysis.setMfcc1Mean(getBigDecimal(timbral, "mfcc_1_mean"));
            analysis.setMfcc1Var(getBigDecimal(timbral, "mfcc_1_var"));
            analysis.setMfcc2Mean(getBigDecimal(timbral, "mfcc_2_mean"));
            analysis.setMfcc2Var(getBigDecimal(timbral, "mfcc_2_var"));
            analysis.setMfcc3Mean(getBigDecimal(timbral, "mfcc_3_mean"));
            analysis.setMfcc3Var(getBigDecimal(timbral, "mfcc_3_var"));
            analysis.setMfcc4Mean(getBigDecimal(timbral, "mfcc_4_mean"));
            analysis.setMfcc4Var(getBigDecimal(timbral, "mfcc_4_var"));
            analysis.setMfcc5Mean(getBigDecimal(timbral, "mfcc_5_mean"));
            analysis.setMfcc5Var(getBigDecimal(timbral, "mfcc_5_var"));
            analysis.setMfcc6Mean(getBigDecimal(timbral, "mfcc_6_mean"));
            analysis.setMfcc6Var(getBigDecimal(timbral, "mfcc_6_var"));
            analysis.setMfcc7Mean(getBigDecimal(timbral, "mfcc_7_mean"));
            analysis.setMfcc7Var(getBigDecimal(timbral, "mfcc_7_var"));
            analysis.setMfcc8Mean(getBigDecimal(timbral, "mfcc_8_mean"));
            analysis.setMfcc8Var(getBigDecimal(timbral, "mfcc_8_var"));
            analysis.setMfcc9Mean(getBigDecimal(timbral, "mfcc_9_mean"));
            analysis.setMfcc9Var(getBigDecimal(timbral, "mfcc_9_var"));
            analysis.setMfcc10Mean(getBigDecimal(timbral, "mfcc_10_mean"));
            analysis.setMfcc10Var(getBigDecimal(timbral, "mfcc_10_var"));
            analysis.setMfcc11Mean(getBigDecimal(timbral, "mfcc_11_mean"));
            analysis.setMfcc11Var(getBigDecimal(timbral, "mfcc_11_var"));
            analysis.setMfcc12Mean(getBigDecimal(timbral, "mfcc_12_mean"));
            analysis.setMfcc12Var(getBigDecimal(timbral, "mfcc_12_var"));
            analysis.setMfcc13Mean(getBigDecimal(timbral, "mfcc_13_mean"));
            analysis.setMfcc13Var(getBigDecimal(timbral, "mfcc_13_var"));

            // Other timbral features
            analysis.setSpectralCentroid(getBigDecimal(timbral, "spectral_centroid"));
            analysis.setSpectralRolloff(getBigDecimal(timbral, "spectral_rolloff"));
            analysis.setDissonance(getBigDecimal(timbral, "dissonance"));

            // Energy features
            JsonNode energy = features.path("energy");
            analysis.setLoudness(getBigDecimal(energy, "loudness"));
            analysis.setDynamicComplexity(getBigDecimal(energy, "dynamic_complexity"));

            log.info("Successfully parsed analysis for track {}: BPM={}, Danceability={}",
                    track.getId(), analysis.getBpm(), analysis.getDanceability());

        } catch (Exception e) {
            log.error("Error parsing features from JSON: {}", e.getMessage(), e);
            analysis.setErrorMessage("Failed to parse features: " + e.getMessage());
        }

        return analysis;
    }

    private BigDecimal getBigDecimal(JsonNode parent, String fieldName) {
        JsonNode node = parent.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse {} as BigDecimal: {}", fieldName, node.asText());
            return null;
        }
    }
}
