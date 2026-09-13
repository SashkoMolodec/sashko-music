package com.sashkomusic.mainagent.search;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

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
                String href = i < titles.size() ? titles.get(i).attr("href") : "";
                String url = resolveRealUrl(href);
                String snippet = snippets.get(i).text();
                if (!title.isBlank()) sb.append("• ").append(title).append(": ");
                sb.append(snippet);
                if (!url.isBlank()) sb.append(" [").append(url).append("]");
                sb.append("\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.error("Web search failed for query='{}': {}", query, e.getMessage());
            return "web search error: " + e.getMessage();
        }
    }

    /**
     * DuckDuckGo's HTML endpoint wraps every result link in a redirect
     * ({@code //duckduckgo.com/l/?uddg=<url-encoded-target>&...}) — the actual target URL was
     * previously discarded entirely, so neither the LLM nor the user could see or verify a
     * source. Decodes the {@code uddg} param back to the real URL.
     */
    private String resolveRealUrl(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        try {
            String queryString = href.contains("?") ? href.substring(href.indexOf('?') + 1) : "";
            for (String param : queryString.split("&")) {
                int eq = param.indexOf('=');
                if (eq > 0 && "uddg".equals(param.substring(0, eq))) {
                    return URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            // Older/alternate DDG markup sometimes links directly instead of via /l/?uddg=
            return href.startsWith("//") ? "https:" + href : href;
        } catch (Exception e) {
            log.debug("Could not resolve real URL from DDG redirect '{}': {}", href, e.getMessage());
            return "";
        }
    }
}
