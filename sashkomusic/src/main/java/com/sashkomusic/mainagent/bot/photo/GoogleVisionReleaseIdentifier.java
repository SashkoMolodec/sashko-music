package com.sashkomusic.mainagent.bot.photo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Identifies a physical release from a photo via Google Cloud Vision's Web Detection feature —
 * the same reverse-image-matching index behind Google Images "visual matches", which reliably
 * surfaces the exact Discogs listing for a record label/sleeve photo. No-op (returns empty) when
 * google.vision.api.key is unset, so callers must fall back to LLM-based text extraction.
 */
@Component
@Slf4j
public class GoogleVisionReleaseIdentifier {

    private static final Pattern DISCOGS_URL = Pattern.compile(
            "https?://[^\\s\"]*discogs\\.com/(?:[^\\s\"/]+/)?(?:release|master)/\\d+[^\\s\"]*",
            Pattern.CASE_INSENSITIVE);

    private final RestClient client;
    private final String apiKey;

    public GoogleVisionReleaseIdentifier(RestClient.Builder builder,
                                          @Value("${google.vision.api.key:}") String apiKey) {
        this.apiKey = apiKey;
        this.client = builder.baseUrl("https://vision.googleapis.com").build();
    }

    public Optional<String> identifyDiscogsUrl(byte[] imageBytes) {
        if (apiKey.isBlank()) {
            log.debug("Google Vision disabled: google.vision.api.key not set");
            return Optional.empty();
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("type", "WEB_DETECTION");
            feature.put("maxResults", 15);

            Map<String, Object> singleRequest = new LinkedHashMap<>();
            singleRequest.put("image", Map.of("content", base64));
            singleRequest.put("features", List.of(feature));

            Map<String, Object> body = Map.of("requests", List.of(singleRequest));

            AnnotateImagesResponse response = client.post()
                    .uri(uriBuilder -> uriBuilder.path("/v1/images:annotate").queryParam("key", apiKey).build())
                    .body(body)
                    .retrieve()
                    .body(AnnotateImagesResponse.class);

            Optional<String> discogsUrl = extractDiscogsUrl(response);
            if (discogsUrl.isPresent()) {
                log.info("Google Vision matched Discogs URL: {}", discogsUrl.get());
            } else {
                log.info("Google Vision web detection found no Discogs match");
            }
            return discogsUrl;
        } catch (Exception e) {
            log.warn("Google Vision web detection failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> extractDiscogsUrl(AnnotateImagesResponse response) {
        if (response == null || response.responses() == null || response.responses().isEmpty()) {
            return Optional.empty();
        }
        WebDetection webDetection = response.responses().getFirst().webDetection();
        if (webDetection == null || webDetection.pagesWithMatchingImages() == null) {
            return Optional.empty();
        }
        return webDetection.pagesWithMatchingImages().stream()
                .map(WebPage::url)
                .filter(Objects::nonNull)
                .filter(url -> DISCOGS_URL.matcher(url).find())
                .findFirst();
    }

    record AnnotateImagesResponse(List<AnnotateImageResponse> responses) {}
    record AnnotateImageResponse(WebDetection webDetection) {}
    record WebDetection(List<WebPage> pagesWithMatchingImages) {}
    record WebPage(String url) {}
}
