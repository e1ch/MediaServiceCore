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
 * Fetches trending topics from Reddit's public JSON API.
 * Uses unauthenticated HTTP — does not affect user's YouTube algorithm.
 *
 * We query Reddit's r/popular hot posts to extract trending topics.
 * Reddit's public API returns JSON without authentication for read-only access.
 *
 * Refresh interval: 2 hours (conservative to respect rate limits).
 */
public class RedditTrendsFetcher implements TrendingFetcher {
    private static final String TAG = RedditTrendsFetcher.class.getSimpleName();
    private static final String API_URL = "https://www.reddit.com/r/popular/hot.json?limit=15&raw_json=1";
    private static final long REFRESH_INTERVAL_MS = 2 * 60 * 60 * 1000L; // 2 hours
    private static final int MAX_KEYWORDS = 8;

    // Skip subreddits that won't produce good YouTube results
    private static final Set<String> SKIP_SUBREDDITS = new HashSet<>();
    static {
        SKIP_SUBREDDITS.add("AskReddit");
        SKIP_SUBREDDITS.add("memes");
        SKIP_SUBREDDITS.add("pics");
        SKIP_SUBREDDITS.add("funny");
        SKIP_SUBREDDITS.add("aww");
        SKIP_SUBREDDITS.add("todayilearned");
        SKIP_SUBREDDITS.add("tifu");
        SKIP_SUBREDDITS.add("AmItheAsshole");
        SKIP_SUBREDDITS.add("unpopularopinion");
    }

    private final OkHttpClient mHttpClient;

    public RedditTrendsFetcher(OkHttpClient httpClient) {
        mHttpClient = httpClient;
    }

    @Override
    public List<TrendingKeyword> fetch(String country, String language) {
        List<TrendingKeyword> keywords = new ArrayList<>();

        try {
            Request request = new Request.Builder()
                    .url(API_URL)
                    .header("User-Agent", "SmartTube/1.0")
                    .build();

            Response response = mHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.d(TAG, "Reddit HTTP " + response.code());
                response.close();
                return keywords;
            }

            String body = response.body().string();
            response.close();

            JSONObject json = new JSONObject(body);
            JSONObject data = json.optJSONObject("data");
            if (data == null) return keywords;

            JSONArray children = data.optJSONArray("children");
            if (children == null) return keywords;

            Set<String> seen = new HashSet<>();
            for (int i = 0; i < children.length() && keywords.size() < MAX_KEYWORDS; i++) {
                JSONObject post = children.getJSONObject(i).optJSONObject("data");
                if (post == null) continue;

                String subreddit = post.optString("subreddit", "");
                if (SKIP_SUBREDDITS.contains(subreddit)) continue;

                String title = post.optString("title", "");
                if (title.isEmpty() || title.length() < 10) continue;

                // Extract key phrase: truncate to first sentence or 60 chars
                String phrase = title;
                int periodIdx = phrase.indexOf(". ");
                if (periodIdx > 10 && periodIdx < 60) {
                    phrase = phrase.substring(0, periodIdx);
                }
                if (phrase.length() > 60) {
                    phrase = phrase.substring(0, 60);
                }

                // Remove common Reddit prefixes
                phrase = phrase.replaceAll("^(TIL |ELI5 |CMV |\\[OC\\] )", "").trim();

                String normalized = phrase.toLowerCase();
                if (seen.contains(normalized)) continue;
                seen.add(normalized);

                TrendingKeyword keyword = new TrendingKeyword(phrase, phrase, getSourceName());
                keyword.setCountry(country);
                keyword.setLanguage(language);
                keywords.add(keyword);
            }

            Log.d(TAG, "Reddit: " + keywords.size() + " topics");
        } catch (Exception e) {
            Log.d(TAG, "Reddit fetch error: " + e.getMessage());
        }

        return keywords;
    }

    @Override
    public String getSourceName() {
        return "Reddit";
    }

    @Override
    public long getRefreshIntervalMs() {
        return REFRESH_INTERVAL_MS;
    }
}
