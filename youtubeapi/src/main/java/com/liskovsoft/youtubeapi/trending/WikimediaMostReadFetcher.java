package com.liskovsoft.youtubeapi.trending;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches trending topics from Wikimedia Most Read API.
 * Uses unauthenticated HTTP — does not affect user's YouTube algorithm.
 *
 * Wikimedia has 300+ language editions. We query yesterday's most-read articles
 * for the user's language to identify cross-cultural trending topics.
 *
 * API: https://wikimedia.org/api/rest_v1/metrics/pageviews/top/{project}/all-access/{date}
 */
public class WikimediaMostReadFetcher implements TrendingFetcher {
    private static final String TAG = WikimediaMostReadFetcher.class.getSimpleName();
    private static final String API_URL =
            "https://wikimedia.org/api/rest_v1/metrics/pageviews/top/%s.wikipedia/all-access/%s";
    private static final long REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private static final int MAX_KEYWORDS = 8;

    // Articles to skip (meta pages, not real topics)
    private static final Set<String> SKIP_TITLES = new HashSet<>();
    static {
        SKIP_TITLES.add("Main_Page");
        SKIP_TITLES.add("Special:Search");
        SKIP_TITLES.add("-");
        SKIP_TITLES.add("Wikipedia:首页");
        SKIP_TITLES.add("メインページ");
        SKIP_TITLES.add("위키백과:대문");
    }

    private final OkHttpClient mHttpClient;

    public WikimediaMostReadFetcher(OkHttpClient httpClient) {
        mHttpClient = httpClient;
    }

    @Override
    public List<TrendingKeyword> fetch(String country, String language) {
        List<TrendingKeyword> keywords = new ArrayList<>();
        String wikiLang = language != null ? language.toLowerCase() : "en";
        // Handle Chinese variants
        if (wikiLang.equals("zh")) wikiLang = "zh";

        // Get yesterday's date in YYYY/MM/DD format
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -1);
        String date = new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(cal.getTime());
        String url = String.format(API_URL, wikiLang, date);

        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "SmartTube/1.0 (https://github.com/e1ch/SmartTube)")
                    .build();

            Response response = mHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.d(TAG, "Wikimedia HTTP " + response.code());
                response.close();
                return keywords;
            }

            String body = response.body().string();
            response.close();

            JSONObject json = new JSONObject(body);
            JSONArray items = json.optJSONArray("items");
            if (items == null || items.length() == 0) return keywords;

            JSONArray articles = items.getJSONObject(0).optJSONArray("articles");
            if (articles == null) return keywords;

            for (int i = 0; i < articles.length() && keywords.size() < MAX_KEYWORDS; i++) {
                JSONObject article = articles.getJSONObject(i);
                String rawTitle = article.optString("article", "");
                if (rawTitle.isEmpty() || SKIP_TITLES.contains(rawTitle)) continue;

                // Convert underscores to spaces for search query
                String title = rawTitle.replace("_", " ");
                // Skip if it looks like a meta page
                if (title.startsWith("Special:") || title.startsWith("Wikipedia:")) continue;

                TrendingKeyword keyword = new TrendingKeyword(title, title, getSourceName());
                keyword.setCountry(country);
                keyword.setLanguage(language);
                keywords.add(keyword);
            }

            Log.d(TAG, "Wikimedia: " + keywords.size() + " keywords for " + wikiLang);
        } catch (Exception e) {
            Log.d(TAG, "Wikimedia fetch error: " + e.getMessage());
        }

        return keywords;
    }

    @Override
    public String getSourceName() {
        return "Wikimedia";
    }

    @Override
    public long getRefreshIntervalMs() {
        return REFRESH_INTERVAL_MS;
    }
}
