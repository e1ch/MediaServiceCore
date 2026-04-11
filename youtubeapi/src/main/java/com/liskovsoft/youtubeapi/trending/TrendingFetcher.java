package com.liskovsoft.youtubeapi.trending;

import java.util.List;

/**
 * Interface for trending keyword sources.
 * All implementations must use unauthenticated HTTP (no OAuth tokens)
 * to avoid affecting the signed-in user's YouTube algorithm.
 */
public interface TrendingFetcher {
    /**
     * Fetch trending keywords for the given country and language.
     * Must be called on a background thread.
     *
     * @param country ISO country code (e.g. "US", "TW", "JP")
     * @param language ISO language code (e.g. "en", "zh", "ja")
     * @return list of trending keywords, empty if fetch fails
     */
    List<TrendingKeyword> fetch(String country, String language);

    /**
     * Returns the source name for logging and UI display.
     */
    String getSourceName();

    /**
     * Returns the recommended refresh interval in milliseconds.
     */
    long getRefreshIntervalMs();
}
