package com.sashkomusic.mainagent.search.client.discogs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscogsMarketplaceListingResponse(Release release) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(Long id) {
    }
}
