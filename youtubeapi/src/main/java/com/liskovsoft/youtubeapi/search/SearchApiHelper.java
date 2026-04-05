package com.liskovsoft.youtubeapi.search;

import com.liskovsoft.youtubeapi.common.helpers.PostDataHelper;
import com.liskovsoft.youtubeapi.search.models.SearchResult;

public class SearchApiHelper {
    private static final String FIRST_SEARCH = "\"query\":\"%s\"";
    private static final String FIRST_SEARCH_EXT = "\"query\":\"%s\",\"params\":\"%s\"";
    private static final String CONTINUATION_SEARCH = "\"continuation\":\"%s\"";

    public static String getSearchQuery(String searchText) {
        return getSearchQuery(searchText, -1);
    }

    /**
     * User-facing search: uses TV client (original SmartTube behavior).
     * Fast, works with TV auth, returns compactVideoRenderer format.
     */
    public static String getSearchQuery(String searchText, int options) {
        String params = SearchFilterHelper.toParams(options);
        String search = params != null ?
                String.format(FIRST_SEARCH_EXT, escape(searchText), params) : String.format(FIRST_SEARCH, escape(searchText));
        return PostDataHelper.createQueryTV(search);
    }

    /**
     * Home fallback search: uses WEB client.
     * Returns videoRenderer format (needed for our SearchResult WEB JsonPath).
     * Used by BrowseService2 for home content discovery.
     */
    public static String getSearchQueryWeb(String searchText, int options) {
        String params = SearchFilterHelper.toParams(options);
        String search = params != null ?
                String.format(FIRST_SEARCH_EXT, escape(searchText), params) : String.format(FIRST_SEARCH, escape(searchText));
        return PostDataHelper.createQueryWeb(search);
    }

    public static String getSearchQueryWeb(String searchText) {
        return getSearchQueryWeb(searchText, -1);
    }

    /**
     * Get data param for the next search
     * @param nextPageKey {@link SearchResult#getNextPageKey()}
     * @return data param
     */
    public static String getContinuationQuery(String nextPageKey) {
        String continuation = String.format(CONTINUATION_SEARCH, nextPageKey);
        return PostDataHelper.createQueryTV(continuation);
    }

    private static String escape(String text) {
        return text
                .replaceAll("'", "\\\\'")
                .replaceAll("\"", "\\\\\"");
    }
}
