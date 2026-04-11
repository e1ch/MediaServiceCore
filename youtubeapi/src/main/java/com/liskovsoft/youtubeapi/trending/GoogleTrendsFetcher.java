package com.liskovsoft.youtubeapi.trending;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches trending keywords from Google Trends Trending Now via RSS feed.
 * Uses unauthenticated HTTP — does not affect user's YouTube algorithm.
 *
 * RSS endpoint: https://trends.google.com/trending/rss?geo={COUNTRY}
 * Updates approximately every 10 minutes per Google's documentation.
 */
public class GoogleTrendsFetcher implements TrendingFetcher {
    private static final String TAG = GoogleTrendsFetcher.class.getSimpleName();
    private static final String RSS_URL = "https://trends.google.com/trending/rss?geo=%s";
    private static final long REFRESH_INTERVAL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final int MAX_KEYWORDS = 10;

    private final OkHttpClient mHttpClient;

    public GoogleTrendsFetcher(OkHttpClient httpClient) {
        mHttpClient = httpClient;
    }

    @Override
    public List<TrendingKeyword> fetch(String country, String language) {
        List<TrendingKeyword> keywords = new ArrayList<>();
        String url = String.format(RSS_URL, country != null ? country.toUpperCase() : "US");

        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept-Language", language != null ? language : "en")
                    .build();

            Response response = mHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.d(TAG, "Google Trends RSS HTTP " + response.code());
                response.close();
                return keywords;
            }

            // Simple XML parsing for <title> elements inside <item>
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), "UTF-8"));
            String line;
            boolean inItem = false;
            int count = 0;

            while ((line = reader.readLine()) != null && count < MAX_KEYWORDS) {
                line = line.trim();
                if (line.contains("<item>")) {
                    inItem = true;
                } else if (line.contains("</item>")) {
                    inItem = false;
                } else if (inItem && line.startsWith("<title>")) {
                    String title = extractXmlContent(line, "title");
                    if (title != null && !title.isEmpty()) {
                        TrendingKeyword keyword = new TrendingKeyword(title, title, getSourceName());
                        keyword.setCountry(country);
                        keyword.setLanguage(language);
                        keywords.add(keyword);
                        count++;
                    }
                }
            }
            reader.close();
            response.close();

            Log.d(TAG, "Google Trends: " + keywords.size() + " keywords for " + country);
        } catch (Exception e) {
            Log.d(TAG, "Google Trends fetch error: " + e.getMessage());
        }

        return keywords;
    }

    @Override
    public String getSourceName() {
        return "GoogleTrends";
    }

    @Override
    public long getRefreshIntervalMs() {
        return REFRESH_INTERVAL_MS;
    }

    private static String extractXmlContent(String line, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = line.indexOf(open);
        int end = line.indexOf(close);
        if (start >= 0 && end > start) {
            return line.substring(start + open.length(), end).trim();
        }
        return null;
    }
}
