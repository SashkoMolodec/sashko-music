package com.sashkomusic.downloadagent.infrastructure.client.bandcamp;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class BandcampSearchClient {

    private final RestClient client;

    public BandcampSearchClient(RestClient.Builder builder) {
        this.client = builder
                .baseUrl("https://bandcamp.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .build();
    }

    public List<BandcampSearchResult> search(String artist, String release) {
        String query = artist + " " + release;
        log.info("Searching Bandcamp: query='{}'", query);

        try {
            String html = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("q", query)
                            .build())
                    .retrieve()
                    .body(String.class);

            if (html == null || html.isEmpty()) {
                log.warn("Empty response from Bandcamp search");
                return List.of();
            }

            return parseSearchResults(html);

        } catch (Exception ex) {
            log.error("Error searching Bandcamp: {}", ex.getMessage(), ex);
            return List.of();
        }
    }

    private List<BandcampSearchResult> parseSearchResults(String html) {
        List<BandcampSearchResult> results = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);
            var resultElements = doc.select("li.searchresult");

            log.info("Found {} search result elements", resultElements.size());

            for (Element element : resultElements) {
                try {
                    String type = extractType(element);

                    // Only albums and tracks can be downloaded
                    if (!type.equals("album") && !type.equals("track")) {
                        continue;
                    }

                    String artist = extractArtist(element);
                    String title = extractTitle(element);
                    String url = extractUrl(element);

                    if (!title.isEmpty() && !url.isEmpty()) {
                        results.add(new BandcampSearchResult(artist, title, type, url));
                    }
                } catch (Exception ex) {
                    log.warn("Error parsing search result element: {}", ex.getMessage());
                }
            }

            log.info("Parsed {} valid Bandcamp results", results.size());

        } catch (Exception ex) {
            log.error("Error parsing Bandcamp search results: {}", ex.getMessage(), ex);
        }

        return results.stream().limit(10).toList();
    }

    private String extractType(Element element) {
        Element typeEl = element.selectFirst("div.itemtype");
        if (typeEl != null) {
            String text = typeEl.text().toLowerCase();
            if (text.contains("album")) return "album";
            if (text.contains("track")) return "track";
        }
        return "unknown";
    }

    private String extractArtist(Element element) {
        Element artistEl = element.selectFirst("div.subhead");
        return artistEl != null ? artistEl.text().replace("by ", "").trim() : "Unknown Artist";
    }

    private String extractTitle(Element element) {
        Element titleEl = element.selectFirst("div.heading a");
        return titleEl != null ? titleEl.text().trim() : "";
    }

    private String extractUrl(Element element) {
        Element linkEl = element.selectFirst("div.heading a");
        if (linkEl != null) {
            String href = linkEl.attr("href");
            // Ensure full URL
            if (href.startsWith("/")) {
                return "https://bandcamp.com" + href;
            }
            return href;
        }
        return "";
    }
}
