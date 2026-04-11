package com.liskovsoft.youtubeapi.trending;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import kotlin.Pair;
import okhttp3.OkHttpClient;

/**
 * Manages trending keyword fetchers and caching.
 * All fetchers use unauthenticated HTTP to avoid affecting signed-in user's algorithm.
 *
 * <p>Usage pattern (matches BrowseService2 companion object pattern):
 * <pre>
 *   // App layer sets enabled sources
 *   TrendingKeywordManager.setSourceEnabled(0, true);  // Google Trends
 *   TrendingKeywordManager.setSourceEnabled(1, true);  // GDELT
 *
 *   // Background refresh
 *   TrendingKeywordManager.instance().refreshIfStale(country, language);
 *
 *   // Get cached keywords as query pairs for BrowseService2
 *   List<Pair<String, String>> queries = TrendingKeywordManager.getCachedAsQueryPairs();
 * </pre>
 */
public class TrendingKeywordManager {
    private static final String TAG = TrendingKeywordManager.class.getSimpleName();

    public static final int SOURCE_GOOGLE_TRENDS = 0;
    public static final int SOURCE_GDELT = 1;
    public static final int SOURCE_WIKIMEDIA = 2;
    public static final int SOURCE_TIKTOK = 3;
    public static final int SOURCE_REDDIT = 4;
    public static final int SOURCE_COUNT = 5;

    private static volatile TrendingKeywordManager sInstance;

    private final List<TrendingFetcher> mFetchers;
    private final List<Long> mLastFetchTimes;

    // Cached keywords — thread-safe, replaced atomically per source
    private static volatile List<TrendingKeyword> sCachedKeywords = new CopyOnWriteArrayList<>();

    // Source enable bitmask — set by app layer from GeneralData
    private static volatile int sEnabledSources = 0x1F; // all 5 enabled by default

    // Serialized cache for persistence across restarts
    private static volatile String sCachedJson = null;
    private static volatile long sCachedTimestamp = 0;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30 minutes

    private TrendingKeywordManager(OkHttpClient httpClient) {
        mFetchers = new ArrayList<>();
        mFetchers.add(new GoogleTrendsFetcher(httpClient));
        mFetchers.add(new GdeltFetcher(httpClient));
        mFetchers.add(new WikimediaMostReadFetcher(httpClient));
        mFetchers.add(new TikTokTrendsFetcher(httpClient));
        mFetchers.add(new RedditTrendsFetcher(httpClient));

        mLastFetchTimes = new ArrayList<>();
        for (int i = 0; i < SOURCE_COUNT; i++) {
            mLastFetchTimes.add(0L);
        }
    }

    public static TrendingKeywordManager instance() {
        if (sInstance == null) {
            throw new IllegalStateException("TrendingKeywordManager not initialized. Call init() first.");
        }
        return sInstance;
    }

    /**
     * Initialize with an unauthenticated OkHttpClient (the same plainHttpClient from BrowseService2).
     * Must be called before first use.
     */
    public static void init(OkHttpClient httpClient) {
        if (sInstance == null) {
            synchronized (TrendingKeywordManager.class) {
                if (sInstance == null) {
                    sInstance = new TrendingKeywordManager(httpClient);
                }
            }
        }
    }

    public static boolean isInitialized() {
        return sInstance != null;
    }

    /**
     * Enable/disable a trending source by index.
     * Called from GeneralData preference system.
     */
    public static void setSourceEnabled(int index, boolean enabled) {
        if (enabled) {
            sEnabledSources |= (1 << index);
        } else {
            sEnabledSources &= ~(1 << index);
        }
    }

    public static boolean isSourceEnabled(int index) {
        return (sEnabledSources & (1 << index)) != 0;
    }

    public static void setEnabledSourcesMask(int mask) {
        sEnabledSources = mask;
    }

    public static int getEnabledSourcesMask() {
        return sEnabledSources;
    }

    /**
     * Refresh keywords from all enabled sources whose TTL has expired.
     * Call from background thread (e.g. after SplashActivity prewarm).
     */
    public void refreshIfStale(String country, String language) {
        long now = System.currentTimeMillis();
        List<TrendingKeyword> allKeywords = new ArrayList<>(sCachedKeywords);
        boolean changed = false;

        for (int i = 0; i < mFetchers.size(); i++) {
            if (!isSourceEnabled(i)) continue;

            TrendingFetcher fetcher = mFetchers.get(i);
            long lastFetch = mLastFetchTimes.get(i);
            if (now - lastFetch < fetcher.getRefreshIntervalMs()) continue;

            try {
                Log.d(TAG, "Refreshing " + fetcher.getSourceName() + " for " + country + "/" + language);
                List<TrendingKeyword> fresh = fetcher.fetch(country, language);
                if (!fresh.isEmpty()) {
                    // Remove old keywords from this source
                    String sourceName = fetcher.getSourceName();
                    allKeywords.removeIf(k -> sourceName.equals(k.getSource()));
                    allKeywords.addAll(fresh);
                    changed = true;
                }
                mLastFetchTimes.set(i, now);
            } catch (Exception e) {
                Log.d(TAG, "Refresh error for " + fetcher.getSourceName() + ": " + e.getMessage());
            }
        }

        if (changed) {
            sCachedKeywords = new CopyOnWriteArrayList<>(allKeywords);
            sCachedJson = serializeKeywords(allKeywords);
            sCachedTimestamp = now;
            Log.d(TAG, "Trending cache updated: " + allKeywords.size() + " keywords");
        }
    }

    /**
     * Get cached keywords as (shelfTitle, searchQuery) pairs for BrowseService2.
     * Thread-safe, returns snapshot.
     */
    public static List<Pair<String, String>> getCachedAsQueryPairs() {
        List<Pair<String, String>> pairs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TrendingKeyword keyword : sCachedKeywords) {
            if (!isSourceEnabled(getSourceIndex(keyword.getSource()))) continue;
            String normalized = keyword.getQuery().toLowerCase();
            if (seen.contains(normalized)) continue;
            seen.add(normalized);
            pairs.add(new Pair<>(keyword.getShelfTitle(), keyword.getQuery()));
        }
        return pairs;
    }

    /**
     * Load cached keywords from persisted JSON (SharedPreferences).
     * Call on cold start to show keywords immediately.
     */
    public static void loadFromJson(String json, long timestamp) {
        if (json == null || json.isEmpty()) return;
        sCachedJson = json;
        sCachedTimestamp = timestamp;
        sCachedKeywords = new CopyOnWriteArrayList<>(deserializeKeywords(json));
        Log.d(TAG, "Loaded " + sCachedKeywords.size() + " cached trending keywords");
    }

    public static String getCachedJson() {
        return sCachedJson;
    }

    public static long getCachedTimestamp() {
        return sCachedTimestamp;
    }

    public static boolean isCacheValid() {
        return sCachedJson != null && (System.currentTimeMillis() - sCachedTimestamp) < CACHE_TTL_MS;
    }

    // --- Serialization (matches BrowseService2 pool cache pattern) ---

    private static String serializeKeywords(List<TrendingKeyword> keywords) {
        JSONArray arr = new JSONArray();
        for (TrendingKeyword kw : keywords) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("query", kw.getQuery());
                obj.put("shelfTitle", kw.getShelfTitle());
                obj.put("source", kw.getSource());
                obj.put("country", kw.getCountry() != null ? kw.getCountry() : "");
                obj.put("language", kw.getLanguage() != null ? kw.getLanguage() : "");
                obj.put("timestamp", kw.getTimestamp());
                arr.put(obj);
            } catch (Exception e) {
                // skip
            }
        }
        return arr.toString();
    }

    private static List<TrendingKeyword> deserializeKeywords(String json) {
        List<TrendingKeyword> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                TrendingKeyword kw = new TrendingKeyword(
                        obj.optString("query", ""),
                        obj.optString("shelfTitle", ""),
                        obj.optString("source", "")
                );
                kw.setCountry(obj.optString("country", ""));
                kw.setLanguage(obj.optString("language", ""));
                kw.setTimestamp(obj.optLong("timestamp", 0));
                if (!kw.getQuery().isEmpty()) {
                    result.add(kw);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Deserialize error: " + e.getMessage());
        }
        return result;
    }

    private static int getSourceIndex(String sourceName) {
        if (sourceName == null) return -1;
        switch (sourceName) {
            case "GoogleTrends": return SOURCE_GOOGLE_TRENDS;
            case "GDELT": return SOURCE_GDELT;
            case "Wikimedia": return SOURCE_WIKIMEDIA;
            case "TikTok": return SOURCE_TIKTOK;
            case "Reddit": return SOURCE_REDDIT;
            default: return -1;
        }
    }
}
