package com.sashkomusic.mainagent.search;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WebSearchService {

    private static final String DDG_URL = "https://html.duckduckgo.com/html/";
    private static final int MAX_RESULTS = 4;
    private static final int TIMEOUT_MS = 8_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    public String search(String query) {
        try {
            Document doc = Jsoup.connect(DDG_URL)
                    .data("q", query)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .post();
            Elements snippets = doc.select(".result__snippet");
            Elements titles = doc.select("a.result__a");
            if (snippets.isEmpty()) {
                log.warn("DuckDuckGo returned no results for query='{}'", query);
                return "no web results found for: " + query;
            }
            var sb = new StringBuilder();
            for (int i = 0; i < Math.min(MAX_RESULTS, snippets.size()); i++) {
                String title = i < titles.size() ? titles.get(i).text() : "";
                String snippet = snippets.get(i).text();
                if (!title.isBlank()) sb.append("• ").append(title).append(": ");
                sb.append(snippet).append("\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.error("Web search failed for query='{}': {}", query, e.getMessage());
            return "web search error: " + e.getMessage();
        }
    }
}
