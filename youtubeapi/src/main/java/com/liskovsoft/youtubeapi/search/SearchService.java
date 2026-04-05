package com.liskovsoft.youtubeapi.search;

import androidx.annotation.NonNull;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.browse.v1.BrowseService;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.googlecommon.common.locale.LocaleManager;
import com.liskovsoft.youtubeapi.search.models.SearchResult;
import com.liskovsoft.youtubeapi.search.models.SearchResultContinuation;
import com.liskovsoft.youtubeapi.search.models.SearchSection;
import com.liskovsoft.youtubeapi.search.models.SearchTags;
import retrofit2.Call;

import java.util.List;

/**
 * Wraps result from the {@link SearchApi}
 */
public class SearchService {
    private static final String TAG = SearchService.class.getSimpleName();
    private final SearchApi mSearchApi;
    private static okhttp3.OkHttpClient sPlainClient;

    public SearchService() {
        mSearchApi = RetrofitHelper.create(SearchApi.class);
    }

    private static okhttp3.OkHttpClient getPlainClient() {
        if (sPlainClient == null) {
            sPlainClient = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .connectionPool(RetrofitOkHttpHelper.getClient().connectionPool())
                    .build();
        }
        return sPlainClient;
    }

    public SearchResult getSearch(String searchText) {
        return getSearch(searchText, -1);
    }

    public SearchResult getSearch(String searchText, int options) {
        Call<SearchResult> wrapper = mSearchApi.getSearchResult(
                SearchApiHelper.getSearchQuery(searchText, options),
                getAppService().getVisitorData());
        return RetrofitHelper.get(wrapper);
    }

    /**
     * Fast search: streaming JsonReader parser, returns List<MediaGroup> directly.
     * Bypasses JsonPath (5-34s on TV CPU) — targets < 2s total.
     */
    public java.util.List<com.liskovsoft.mediaserviceinterfaces.data.MediaGroup> getSearchFast(String searchText, int options) {
        long t0 = System.currentTimeMillis();
        String searchQuery = SearchApiHelper.getSearchQuery(searchText, options);
        String visitorData = getAppService().getVisitorData();
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?key=" +
                     com.liskovsoft.youtubeapi.common.helpers.AppConstants.API_KEY + "&prettyPrint=false")
                .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), searchQuery));
        if (visitorData != null) rb.addHeader("X-Goog-Visitor-Id", visitorData);

        try {
            okhttp3.Response response = getPlainClient().newCall(rb.build()).execute();
            long t1 = System.currentTimeMillis();
            if (!response.isSuccessful()) {
                Log.e(TAG, "Search HTTP %d for '%s'", response.code(), searchText);
                response.close();
                return null;
            }
            java.util.List<com.liskovsoft.youtubeapi.search.fast.VideoItemLite> items =
                    com.liskovsoft.youtubeapi.search.fast.SearchStreamingParser.parse(response.body().byteStream());
            response.close();
            long t2 = System.currentTimeMillis();
            Log.d(TAG, "getSearchFast '%s': http=%dms, parse=%dms, items=%d", searchText, t1-t0, t2-t1, items.size());

            // Convert to MediaGroup using YouTubeMediaItem (same as kworb/Charts — proven to work)
            java.util.List<com.liskovsoft.mediaserviceinterfaces.data.MediaItem> mediaItems = new java.util.ArrayList<>();
            for (com.liskovsoft.youtubeapi.search.fast.VideoItemLite v : items) {
                com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem mi =
                        new com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem();
                mi.setVideoId(v.videoId);
                mi.setTitle(v.title);
                mi.setAuthor(v.channelName);
                mi.setChannelId(v.channelId);
                mi.setCardImageUrl(v.thumbnailUrl);
                mi.setBadgeText(v.lengthText);
                mi.setSecondTitle(com.liskovsoft.googlecommon.common.helpers.YouTubeHelper.createInfo(
                        v.channelName, v.viewCountText, v.publishedTime));
                mediaItems.add(mi);
            }

            com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup group =
                    new com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup(
                            com.liskovsoft.mediaserviceinterfaces.data.MediaGroup.TYPE_SEARCH);
            group.setMediaItems(new java.util.ArrayList<>(mediaItems));

            java.util.List<com.liskovsoft.mediaserviceinterfaces.data.MediaGroup> result = new java.util.ArrayList<>();
            result.add(group);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Search error for '%s': %s", searchText, e.getMessage());
            return null;
        }
    }

    /**
     * Method uses results from the {@link #getSearch(String)} call
     * @return video items
     */
    public SearchResultContinuation continueSearch(String nextSearchPageKey) {
        if (nextSearchPageKey == null) {
            Log.e(TAG, "Can't get next search page. Next search key is empty.");
            return null;
        }

        Call<SearchResultContinuation> wrapper = mSearchApi.continueSearchResult(SearchApiHelper.getContinuationQuery(nextSearchPageKey));
        SearchResultContinuation searchResult = RetrofitHelper.get(wrapper);

        if (searchResult == null) {
            Log.e(TAG, "Empty next search page result for key %s", nextSearchPageKey);
        }

        return searchResult;
    }

    public List<String> getSearchTags(String searchText) {
        String country = null;
        String language = null;

        LocaleManager localeManager = LocaleManager.instance();
        country = localeManager.getCountry();
        // fix empty popular searches (country and language should match or use only country)
        //language = localeManager.getLanguage();

        return getSearchTags(searchText, null, country, language);
    }

    private List<String> getSearchTags(String searchText, String suggestToken, String country, String language) {
        if (searchText == null) {
            searchText = "";
        }

        Call<SearchTags> wrapper =
                mSearchApi.getSearchTags(
                        searchText,
                        suggestToken,
                        country,
                        language
                );
        SearchTags searchTags = RetrofitHelper.get(wrapper);

        if (searchTags != null && searchTags.getSearchTags() != null) {
            return searchTags.getSearchTags();
        }

        return null;
    }

    public void clearSearchHistory() {
        // NOP
    }

    @NonNull
    private static AppService getAppService() {
        return AppService.instance();
    }

    @NonNull
    private static BrowseService getBrowseService() {
        return BrowseService.instance();
    }
}
