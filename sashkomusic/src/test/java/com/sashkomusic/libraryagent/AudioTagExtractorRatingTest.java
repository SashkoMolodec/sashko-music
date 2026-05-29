package com.sashkomusic.libraryagent;

import com.sashkomusic.libraryagent.domain.service.utils.AudioTagExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for AudioTagExtractor rating functionality.
 * Tests writing and reading RATING and RATING WMP tags for FLAC files.
 */
class AudioTagExtractorRatingTest {

    private AudioTagExtractor extractor;
    private Path testFile;

    @BeforeEach
    void setUp() {
        extractor = new AudioTagExtractor();
        testFile = Paths.get("/Users/okravch/my/sm/lib/various artists/so so def bass all stars (1996) [flac]/05. trigga man feat. kidd money - shakedown.flac");
    }

    @Test
    void testWriteAndReadRatingWmpForFlac() {
        System.out.println("=== Testing RATING WMP write to FLAC ===");
        System.out.println("File: " + testFile.getFileName());
        System.out.println();

        // Read tags before writing
        System.out.println("--- BEFORE writing ---");
        Map<String, String> tagsBefore = extractor.extractAllTags(testFile);
        String ratingBefore = tagsBefore.get("RATING");
        String ratingWmpBefore = tagsBefore.get("RATING WMP");
        System.out.println("RATING: " + (ratingBefore != null ? ratingBefore : "NOT FOUND"));
        System.out.println("RATING WMP: " + (ratingWmpBefore != null ? ratingWmpBefore : "NOT FOUND"));
        System.out.println();

        // Write rating (3 stars = 153)
        System.out.println("--- WRITING rating 3 stars (WMP: 153) ---");
        boolean success = extractor.writeRating(testFile, 3);
        assertTrue(success, "Writing rating should succeed");
        System.out.println("Write result: SUCCESS");
        System.out.println();

        // Read tags after writing
        System.out.println("--- AFTER writing ---");
        Map<String, String> tagsAfter = extractor.extractAllTags(testFile);
        String ratingAfter = tagsAfter.get("RATING");
        String ratingWmpAfter = tagsAfter.get("RATING WMP");
        System.out.println("RATING: " + (ratingAfter != null ? ratingAfter : "NOT FOUND"));
        System.out.println("RATING WMP: " + (ratingWmpAfter != null ? ratingWmpAfter : "NOT FOUND"));
        System.out.println();

        // Assertions
        assertNotNull(ratingAfter, "RATING tag should be present after writing");
        assertEquals("153", ratingAfter, "RATING should be 153 (3 stars)");

        assertNotNull(ratingWmpAfter, "RATING WMP tag should be present after writing");
        assertEquals("153", ratingWmpAfter, "RATING WMP should be 153 (3 stars)");

        System.out.println("✅ TEST PASSED: Both RATING and RATING WMP are correctly written!");
    }

    @Test
    void testWriteDifferentRatings() {
        System.out.println("=== Testing different rating values ===");
        System.out.println();

        int[] testRatings = {1, 2, 3, 4, 5};
        int[] expectedWmpValues = {51, 102, 153, 204, 255};

        for (int i = 0; i < testRatings.length; i++) {
            int stars = testRatings[i];
            int expectedWmp = expectedWmpValues[i];

            System.out.println("Testing " + stars + " stars (expected WMP: " + expectedWmp + ")");

            boolean success = extractor.writeRating(testFile, stars);
            assertTrue(success, "Writing " + stars + " stars should succeed");

            Map<String, String> tags = extractor.extractAllTags(testFile);
            String rating = tags.get("RATING");
            String ratingWmp = tags.get("RATING WMP");

            assertEquals(String.valueOf(expectedWmp), rating, "RATING should be " + expectedWmp);
            assertEquals(String.valueOf(expectedWmp), ratingWmp, "RATING WMP should be " + expectedWmp);

            System.out.println("  ✓ RATING: " + rating);
            System.out.println("  ✓ RATING WMP: " + ratingWmp);
            System.out.println();
        }

        System.out.println("✅ ALL RATING VALUES TESTED SUCCESSFULLY!");
    }
}
