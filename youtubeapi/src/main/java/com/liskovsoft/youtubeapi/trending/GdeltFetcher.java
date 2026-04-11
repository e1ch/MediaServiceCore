package com.liskovsoft.youtubeapi.trending;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches trending keywords from GDELT DOC 2.0 API.
 * Uses unauthenticated HTTP — does not affect user's YouTube algorithm.
 *
 * GDELT monitors global news in 100+ languages, updates every 15 minutes.
 * We extract top themes/entities from recent articles as YouTube search keywords.
 *
 * API: https://api.gdeltproject.org/api/v2/doc/doc?query=...&format=json
 */
public class GdeltFetcher implements TrendingFetcher {
    private static final String TAG = GdeltFetcher.class.getSimpleName();
    private static final String API_URL =
            "https://api.gdeltproject.org/api/v2/doc/doc?query=sourcelang:%s&mode=ArtList&maxrecords=20&format=json&sort=DateDesc&timespan=1h";
    private static final long REFRESH_INTERVAL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final int MAX_KEYWORDS = 8;

    private final OkHttpClient mHttpClient;

    public GdeltFetcher(OkHttpClient httpClient) {
        mHttpClient = httpClient;
    }

    @Override
    public List<TrendingKeyword> fetch(String country, String language) {
        List<TrendingKeyword> keywords = new ArrayList<>();
        String langCode = mapLanguageToGdelt(language);
        String url = String.format(API_URL, langCode);

        try {
            Request request = new Request.Builder()
                    .url(url)
                    .build();

            Response response = mHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.d(TAG, "GDELT HTTP " + response.code());
                response.close();
                return keywords;
            }

            String body = response.body().string();
            response.close();

            JSONObject json = new JSONObject(body);
            JSONArray articles = json.optJSONArray("articles");
            if (articles == null) {
                return keywords;
            }

            // Extract unique titles as keyword candidates
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < articles.length() && keywords.size() < MAX_KEYWORDS; i++) {
                JSONObject article = articles.getJSONObject(i);
                String title = article.optString("title", "");
                if (title.isEmpty() || title.length() < 5) continue;

                // Extract key phrase: first meaningful segment before " - " (source attribution)
                int dashIdx = title.indexOf(" - ");
                String phrase = dashIdx > 0 ? title.substring(0, dashIdx).trim() : title;
                // Limit length for YouTube search
                if (phrase.length() > 60) {
                    phrase = phrase.substring(0, 60);
                }

                String normalized = phrase.toLowerCase();
                if (seen.contains(normalized)) continue;
                seen.add(normalized);

                TrendingKeyword keyword = new TrendingKeyword(phrase, phrase, getSourceName());
                keyword.setCountry(country);
                keyword.setLanguage(language);
                keywords.add(keyword);
            }

            Log.d(TAG, "GDELT: " + keywords.size() + " keywords for " + langCode);
        } catch (Exception e) {
            Log.d(TAG, "GDELT fetch error: " + e.getMessage());
        }

        return keywords;
    }

    @Override
    public String getSourceName() {
        return "GDELT";
    }

    @Override
    public long getRefreshIntervalMs() {
        return REFRESH_INTERVAL_MS;
    }

    /**
     * Maps ISO language code to GDELT sourcelang code.
     * GDELT uses its own language codes for filtering.
     */
    private static String mapLanguageToGdelt(String language) {
        if (language == null) return "english";
        switch (language.toLowerCase()) {
            case "zh": return "mandarin";
            case "ja": return "japanese";
            case "ko": return "korean";
            case "es": return "spanish";
            case "fr": return "french";
            case "de": return "german";
            case "pt": return "portuguese";
            case "ru": return "russian";
            case "ar": return "arabic";
            case "hi": return "hindi";
            case "th": return "thai";
            case "vi": return "vietnamese";
            case "tr": return "turkish";
            case "it": return "italian";
            case "pl": return "polish";
            case "nl": return "dutch";
            case "sv": return "swedish";
            case "uk": return "ukrainian";
            default: return "english";
        }
    }
}
