package com.liskovsoft.youtubeapi.trending;

/**
 * A single trending keyword extracted from an external source (Google Trends, GDELT, etc.).
 * Converted into a YouTube search query for discovery shelves.
 */
public class TrendingKeyword {
    private String mQuery;
    private String mShelfTitle;
    private String mSource;
    private String mCountry;
    private String mLanguage;
    private long mTimestamp;

    public TrendingKeyword(String query, String shelfTitle, String source) {
        mQuery = query;
        mShelfTitle = shelfTitle;
        mSource = source;
        mTimestamp = System.currentTimeMillis();
    }

    public String getQuery() {
        return mQuery;
    }

    public void setQuery(String query) {
        mQuery = query;
    }

    public String getShelfTitle() {
        return mShelfTitle;
    }

    public void setShelfTitle(String shelfTitle) {
        mShelfTitle = shelfTitle;
    }

    public String getSource() {
        return mSource;
    }

    public String getCountry() {
        return mCountry;
    }

    public void setCountry(String country) {
        mCountry = country;
    }

    public String getLanguage() {
        return mLanguage;
    }

    public void setLanguage(String language) {
        mLanguage = language;
    }

    public long getTimestamp() {
        return mTimestamp;
    }

    public void setTimestamp(long timestamp) {
        mTimestamp = timestamp;
    }
}
