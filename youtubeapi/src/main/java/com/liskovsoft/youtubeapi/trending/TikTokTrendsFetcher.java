package com.liskovsoft.youtubeapi.trending;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches trending hashtags from TikTok Creative Center.
 * Uses unauthenticated HTTP — does not affect user's YouTube algorithm.
 *
 * TikTok Creative Center shows regional trending hashtags, songs, and creators.
 * We extract hashtag names and convert them to YouTube search queries.
 *
 * Note: No official API; we scrape the public trending page.
 * Refresh interval is conservative (1-3 hours) to avoid rate limiting.
 */
public class TikTokTrendsFetcher implements TrendingFetcher {
    private static final String TAG = TikTokTrendsFetcher.class.getSimpleName();
    private static final String TRENDING_URL =
            "https://ads.tiktok.com/business/creativecenter/inspiration/popular/hashtag/pc/en?period=7&page=1&sort_by=popular";
    private static final long REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L; // 2 hours
    private static final int MAX_KEYWORDS = 10;

    // Pattern to extract hashtag names from the page HTML/JSON
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("\"hashtag_name\"\\s*:\\s*\"([^\"]+)\"");

    private final OkHttpClient mHttpClient;

    public TikTokTrendsFetcher(OkHttpClient httpClient) {
        mHttpClient = httpClient;
    }

    @Override
    public List<TrendingKeyword> fetch(String country, String language) {
        List<TrendingKeyword> keywords = new ArrayList<>();

        try {
            Request request = new Request.Builder()
                    .url(TRENDING_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", language != null ? language : "en")
                    .build();

            Response response = mHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.d(TAG, "TikTok HTTP " + response.code());
                response.close();
                return keywords;
            }

            String body = response.body().string();
            response.close();

            Set<String> seen = new HashSet<>();
            Matcher matcher = HASHTAG_PATTERN.matcher(body);

            while (matcher.find() && keywords.size() < MAX_KEYWORDS) {
                String hashtag = matcher.group(1).trim();
                if (hashtag.isEmpty() || hashtag.length() < 2) continue;
                String normalized = hashtag.toLowerCase();
                if (seen.contains(normalized)) continue;
                seen.add(normalized);

                // Convert hashtag to YouTube search query
                String query = hashtag.startsWith("#") ? hashtag.substring(1) : hashtag;
                TrendingKeyword keyword = new TrendingKeyword(query, "#" + query, getSourceName());
                keyword.setCountry(country);
                keyword.setLanguage(language);
                keywords.add(keyword);
            }

            Log.d(TAG, "TikTok: " + keywords.size() + " hashtags");
        } catch (Exception e) {
            Log.d(TAG, "TikTok fetch error: " + e.getMessage());
        }

        return keywords;
    }

    @Override
    public String getSourceName() {
        return "TikTok";
    }

    @Override
    public long getRefreshIntervalMs() {
        return REFRESH_INTERVAL_MS;
    }
}
