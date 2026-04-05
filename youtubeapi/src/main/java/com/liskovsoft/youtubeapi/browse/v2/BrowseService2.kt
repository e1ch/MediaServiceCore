package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.youtubeapi.browse.v2.gen.*
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.*
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.youtubeapi.common.helpers.PostDataHelper
import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.gen.getPlaylistId
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.ShortsMediaItem
import com.liskovsoft.youtubeapi.next.v2.gen.getItems
import com.liskovsoft.youtubeapi.next.v2.gen.getContinuationToken
import com.liskovsoft.youtubeapi.next.v2.gen.getShelves
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import android.util.Log

internal open class BrowseService2 {
    private val mBrowseApi = RetrofitHelper.create(BrowseApi::class.java)
    private val TAG = "BrowseService2"
    private val mSearchApi = RetrofitHelper.create(com.liskovsoft.youtubeapi.search.SearchApi::class.java)
    /**
     * Search using WEB client without auth (always anonymous).
     * When signed in, auth headers cause TV-format responses that our SearchResult can't parse.
     * Uses addAuthSkip to force API-key mode even when signed in.
     */
    private fun searchWithVisitorData(query: String, options: Int = -1): com.liskovsoft.youtubeapi.search.models.SearchResult? {
        val searchQuery = com.liskovsoft.youtubeapi.search.SearchApiHelper.getSearchQuery(query, options)
        val call = mSearchApi.getSearchResult(searchQuery)
        // Tell OkHttp interceptor to skip auth for this request (use API key instead)
        call.request()?.let { RetrofitOkHttpHelper.addAuthSkip(it) }
        return RetrofitHelper.get(call)
    }
    private fun searchWithVisitorData(query: String): com.liskovsoft.youtubeapi.search.models.SearchResult? {
        val searchQuery = com.liskovsoft.youtubeapi.search.SearchApiHelper.getSearchQuery(query)
        val call = mSearchApi.getSearchResult(searchQuery)
        call.request()?.let { RetrofitOkHttpHelper.addAuthSkip(it) }
        return RetrofitHelper.get(call)
    }

    //fun getHome(): List<MediaGroup?>? {
    //    val home = getBrowseRows(BrowseApiHelper.getHomeQueryWeb(), MediaGroup.TYPE_HOME)
    //    return if (home?.size ?: 0 < 5) listOfNotNull(home, getRecommended()).flatten() else home
    //}

    /**
     * Limit shownVideoIds to prevent infinite growth which causes all videos to be filtered.
     * Keep only the most recent 200 IDs.
     */
    private fun trimShownVideoIds() {
        if (shownVideoIds.size > 200) {
            val toRemove = shownVideoIds.size - 100
            val iter = shownVideoIds.iterator()
            var removed = 0
            while (iter.hasNext() && removed < toRemove) {
                iter.next()
                iter.remove()
                removed++
            }
        }
    }

    /**
     * Phase 1: Fast results (~1-3s). Prefetch cache + TV client recommendations.
     * Displayed immediately while Phase 2 loads in background.
     */
    fun getHomeFast(): Pair<List<MediaGroup?>?, String?>? {
        trimShownVideoIds()
        val t0 = System.currentTimeMillis()
        val result = mutableListOf<MediaGroup?>()

        // 1. Prefetched content (instant)
        if (prefetchedGroups.isNotEmpty()) {
            result.addAll(prefetchedGroups)
            prefetchedGroups.clear()
        }

        // 2. TV client browse
        val tvResult = getBrowseRowsTV(BrowseApiHelper::getHomeQuery, MediaGroup.TYPE_HOME)
        val tvGroups = tvResult?.first?.filter { group ->
            group?.mediaItems?.any { it?.videoId != null } == true
        }
        if (!tvGroups.isNullOrEmpty()) {
            result.addAll(tvGroups)
        }

        // Track shown videoIds
        result.forEach { group ->
            group?.mediaItems?.forEach { item ->
                item?.videoId?.let { shownVideoIds.add(it) }
            }
        }

        System.err.println("[PERF] getHomeFast: ${System.currentTimeMillis() - t0}ms, ${result.size} groups")
        return if (result.isNotEmpty()) Pair(result, null) else tvResult
    }

    /**
     * Phase 2: Rich results (~5-15s). Search + kworb trending.
     * Added to the UI after Phase 1 is already visible.
     */
    fun getHomeExtra(): List<MediaGroup?> {
        val t0 = System.currentTimeMillis()
        val queries = getRotatedHomeQueries()
        val searchResults = getSearchFallbackParallel(queries, MediaGroup.TYPE_HOME)
        searchResults.forEach { group ->
            group?.mediaItems?.forEach { item ->
                item?.videoId?.let { shownVideoIds.add(it) }
            }
        }
        System.err.println("[PERF] getHomeExtra: ${System.currentTimeMillis() - t0}ms, ${searchResults.size} groups")
        return searchResults
    }

    /**
     * Streaming version: fires callback for each search result as it arrives.
     * All queries run in parallel; whoever finishes first emits first.
     * kworb also runs in parallel alongside search queries.
     */
    fun streamHomeExtra(onGroupReady: java.util.function.Consumer<MediaGroup?>) {
        val queries = getRotatedHomeQueries()
        if (queries.isEmpty()) return

        val dateFilter = com.liskovsoft.mediaserviceinterfaces.data.SearchOptions.UPLOAD_DATE_THIS_YEAR
        // Only dedup across Phase 2 search results, NOT against Phase 1 TV results.
        // This ensures trending/search content always appears even if TV already showed some.
        val seenIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val excluded = excludedVideoIds // watched videos only

        val executor = java.util.concurrent.Executors.newFixedThreadPool(queries.size.coerceAtMost(4) + 1)

        // Submit ALL searches + kworb concurrently
        val futures = mutableListOf<java.util.concurrent.Future<*>>()

        for ((title, query) in queries) {
            futures.add(executor.submit {
                try {
                    val tq = System.currentTimeMillis()
                    val sr = searchWithVisitorData(query, dateFilter)
                    System.err.println("[PERF] stream search '$query' took ${System.currentTimeMillis() - tq}ms")
                    sr?.let {
                        val groups = YouTubeMediaGroup.from(it, MediaGroup.TYPE_HOME)
                        groups?.firstOrNull()?.let { group ->
                            (group as? YouTubeMediaGroup)?.title = title
                            group.mediaItems?.removeAll { item ->
                                val id = item?.videoId ?: return@removeAll false
                                !seenIds.add(id) || excluded.contains(id)
                            }
                            if (group.isEmpty != true) {
                                group.mediaItems?.forEach { item -> item?.videoId?.let { shownVideoIds.add(it) } }
                                onGroupReady.accept(group)
                            }
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("[PERF] stream search failed: ${e.message}")
                }
            })
        }

        // kworb in parallel
        futures.add(executor.submit {
            try {
                val trendingGroup = fetchKworbTrending(MediaGroup.TYPE_HOME, seenIds as MutableSet<String>, excluded)
                if (trendingGroup != null) {
                    trendingGroup.mediaItems?.forEach { item -> item?.videoId?.let { shownVideoIds.add(it) } }
                    onGroupReady.accept(trendingGroup)
                }
            } catch (e: Exception) {
                System.err.println("[PERF] kworb stream failed: ${e.message}")
            }
        })

        // Wait for all to complete
        for (f in futures) {
            try { f.get(20, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        executor.shutdown()
    }

    /** Legacy single-call (used by non-Observable getHome in YouTubeContentService) */
    fun getHome(): Pair<List<MediaGroup?>?, String?>? {
        val fast = getHomeFast()
        val extra = getHomeExtra()
        val combined = mutableListOf<MediaGroup?>()
        fast?.first?.let { combined.addAll(it) }
        combined.addAll(extra)
        return if (combined.isNotEmpty()) Pair(combined, null) else fast
    }

    /**
     * Rotates through different query sets on each refresh for content variety.
     * Query pools are set by the app layer from string resources via setHomeQueryPools().
     * Falls back to homeQueries if no pools are configured.
     */
    private fun getRotatedHomeQueries(): List<Pair<String, String>> {
        val pools = homeQueryPools
        if (pools.isEmpty()) return homeQueries
        val idx = refreshCounter.getAndIncrement()
        return pools[idx % pools.size]
    }

    /**
     * FEtrending endpoint is deprecated (returns 400 for all clients).
     * Uses search-based trending directly.
     */
    fun getTrending(): List<MediaGroup?>? {
        return getSearchFallbackParallel(trendingQueries, MediaGroup.TYPE_TRENDING)
    }

    /**
     * Search-based fallback for when browse endpoints fail or return empty.
     * Each query produces a named MediaGroup (shelf), similar to PrismTube's approach.
     * Deduplicates videos by videoId across all query results.
     */
    /**
     * Parallel version of search fallback — all queries execute concurrently.
     * Reduces total time from sum(query_times) to max(query_times).
     */
    /**
     * Optimized search: first query runs sequentially (warms up TLS/connection pool),
     * then remaining queries run in parallel. This avoids concurrent initialization
     * conflicts that cause timeouts on cold start.
     */
    /**
     * Search with "this year" upload date filter for fresh content.
     * First query sequential (TLS warmup), rest parallel.
     */
    fun getSearchFallbackParallel(queries: List<Pair<String, String>>, groupType: Int): List<MediaGroup?> {
        if (queries.isEmpty()) return emptyList()

        // Use "This Year" upload date filter to avoid old clickbait
        val dateFilter = com.liskovsoft.mediaserviceinterfaces.data.SearchOptions.UPLOAD_DATE_THIS_YEAR

        val result = mutableListOf<MediaGroup?>()
        val seenIds = mutableSetOf<String>()
        val excluded = excludedVideoIds

        // 1. First query: sequential (warms up TLS + connection pool)
        val first = queries.first()
        val t1 = System.currentTimeMillis()
        val firstResult = searchWithVisitorData(first.second, dateFilter)
        System.err.println("[PERF] first search '${first.second}' took ${System.currentTimeMillis() - t1}ms (warmup)")
        addSearchResult(firstResult, first.first, groupType, result, seenIds, excluded)

        // 2. Remaining queries + kworb trending: parallel
        val remaining = queries.drop(1).toMutableList()
        if (remaining.isNotEmpty()) {
            val executor = java.util.concurrent.Executors.newFixedThreadPool(remaining.size.coerceAtMost(4))
            val futures = remaining.map { (title, query) ->
                executor.submit(java.util.concurrent.Callable {
                    val tq = System.currentTimeMillis()
                    val sr = searchWithVisitorData(query, dateFilter)
                    System.err.println("[PERF] parallel search '$query' took ${System.currentTimeMillis() - tq}ms")
                    Pair(title, sr)
                })
            }
            for (future in futures) {
                try {
                    val (title, sr) = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    addSearchResult(sr, title, groupType, result, seenIds, excluded)
                } catch (e: Exception) {
                    System.err.println("[PERF] search failed: ${e.message}")
                }
            }
            executor.shutdown()
        }

        // 3. Mix in kworb real trending (regional + global)
        try {
            val trendingGroup = fetchKworbTrending(groupType, seenIds, excluded)
            if (trendingGroup != null) {
                result.add(0, trendingGroup) // Put trending first
            }
        } catch (e: Exception) {
            System.err.println("[PERF] kworb fetch failed: ${e.message}")
        }

        return result
    }

    /**
     * Fetches real trending video IDs from kworb.net, then uses /player endpoint
     * to get metadata for each video. /player is lightweight (no search ranking,
     * no auth format issues) and ~30ms per call in parallel.
     */
    private fun fetchKworbTrending(groupType: Int, seenIds: MutableSet<String>, excluded: Set<String>): MediaGroup? {
        val country = com.liskovsoft.googlecommon.common.locale.LocaleManager.instance().country ?: "US"
        val lang = com.liskovsoft.googlecommon.common.locale.LocaleManager.instance().language ?: "en"

        // 1. Fetch kworb trending page
        val t0 = System.currentTimeMillis()
        val url = "https://kworb.net/youtube/trending/${country.lowercase()}.html"
        val httpClient = RetrofitOkHttpHelper.client
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val response = try { httpClient.newCall(request).execute() } catch (e: Exception) { return null }
        if (!response.isSuccessful) { response.close(); return null }
        val body = response.body()?.string() ?: return null
        response.close()
        System.err.println("[PERF] kworb fetch: ${System.currentTimeMillis() - t0}ms")

        // 2. Extract video IDs (top 20, skip watched/seen)
        val videoIds = Regex("youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})")
            .findAll(body)
            .map { it.groupValues[1] }
            .filter { !seenIds.contains(it) && !excluded.contains(it) }
            .distinct()
            .take(20)
            .toList()
        if (videoIds.isEmpty()) return null

        // 3. Fetch metadata via /player (lightweight, no search ranking)
        val t1 = System.currentTimeMillis()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val allItems = java.util.concurrent.CopyOnWriteArrayList<com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem>()

        val playerBody = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20260401.08.00","hl":"$lang","gl":"$country"}},"videoId":"%s"}"""

        val futures = videoIds.map { videoId ->
            executor.submit {
                try {
                    val playerRequest = okhttp3.Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/player?key=${com.liskovsoft.youtubeapi.common.helpers.AppConstants.API_KEY}&prettyPrint=false")
                        .post(okhttp3.RequestBody.create(
                            okhttp3.MediaType.parse("application/json"),
                            String.format(playerBody, videoId)))
                        .build()
                    val playerResponse = httpClient.newCall(playerRequest).execute()
                    if (playerResponse.isSuccessful) {
                        val json = playerResponse.body()?.string()
                        playerResponse.close()
                        json?.let { parsePlayerToVideo(it, videoId) }?.let { allItems.add(it) }
                    } else {
                        playerResponse.close()
                    }
                } catch (_: Exception) {}
            }
        }

        for (f in futures) {
            try { f.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        executor.shutdown()
        System.err.println("[PERF] kworb /player lookups: ${allItems.size}/${videoIds.size} in ${System.currentTimeMillis() - t1}ms")

        if (allItems.isEmpty()) return null

        // 4. Build MediaGroup preserving kworb ranking order
        val orderedItems = videoIds.mapNotNull { id -> allItems.firstOrNull { it.videoId == id } }
        orderedItems.forEach { seenIds.add(it.videoId) }

        val group = YouTubeMediaGroup(groupType)
        group.title = kworbTitle
        group.mediaItems = java.util.ArrayList<com.liskovsoft.mediaserviceinterfaces.data.MediaItem>(orderedItems)
        return group
    }

    /** Parse /player JSON into a YouTubeMediaItem with full metadata for UI display */
    private fun parsePlayerToVideo(json: String, videoId: String): com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem? {
        try {
            val obj = org.json.JSONObject(json)
            val vd = obj.optJSONObject("videoDetails") ?: return null
            val mf = obj.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")

            val item = com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem()
            item.videoId = vd.optString("videoId", videoId)
            item.title = vd.optString("title", "")
            item.author = vd.optString("author", "")

            // Thumbnail (use highest quality)
            val thumbs = vd.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0) {
                item.cardImageUrl = thumbs.getJSONObject(thumbs.length() - 1).optString("url", "")
            }

            // Second title: channel name · view count · upload time (displayed below title)
            val author = vd.optString("author", "")
            val viewCount = formatViewCount(vd.optString("viewCount", ""))
            val publishDate = mf?.optString("publishDate", "") ?: ""
            val timeAgo = if (publishDate.isNotEmpty()) formatTimeAgo(publishDate) else ""
            item.secondTitle = com.liskovsoft.googlecommon.common.helpers.YouTubeHelper.createInfo(
                author, viewCount, timeAgo
            )

            // Live badge
            if (vd.optBoolean("isLiveContent", false) && vd.optBoolean("isLive", false)) {
                item.isLive = true
            }

            return item
        } catch (_: Exception) { return null }
    }

    /** Format view count: 7598293 → "759萬次觀看" */
    private fun formatViewCount(count: String): String {
        val n = count.toLongOrNull() ?: return ""
        if (n <= 0) return ""
        val lang = com.liskovsoft.googlecommon.common.locale.LocaleManager.instance().language ?: "en"
        return when {
            lang.startsWith("zh") -> when {
                n >= 100_000_000 -> "${n / 100_000_000}.${(n % 100_000_000) / 10_000_000}億次觀看"
                n >= 10_000 -> "${n / 10_000}萬次觀看"
                else -> "${n}次觀看"
            }
            lang.startsWith("ko") -> when {
                n >= 100_000_000 -> "${n / 100_000_000}.${(n % 100_000_000) / 10_000_000}억회"
                n >= 10_000 -> "${n / 10_000}만회"
                else -> "${n}회"
            }
            lang.startsWith("ja") -> when {
                n >= 100_000_000 -> "${n / 100_000_000}.${(n % 100_000_000) / 10_000_000}億回"
                n >= 10_000 -> "${n / 10_000}万回"
                else -> "${n}回"
            }
            else -> when {
                n >= 1_000_000_000 -> "${n / 1_000_000_000}.${(n % 1_000_000_000) / 100_000_000}B views"
                n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M views"
                n >= 1_000 -> "${n / 1_000}K views"
                else -> "$n views"
            }
        }
    }

    /** Format ISO date to relative time: "2026-04-01T21:00:02-07:00" → "4 天前" */
    private fun formatTimeAgo(isoDate: String): String {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val date = sdf.parse(isoDate.substring(0, 10)) ?: return ""
            val diffMs = System.currentTimeMillis() - date.time
            val days = diffMs / (24 * 60 * 60 * 1000)
            val lang = com.liskovsoft.googlecommon.common.locale.LocaleManager.instance().language ?: "en"
            return when {
                lang.startsWith("zh") -> when {
                    days < 1 -> "今天"
                    days < 2 -> "昨天"
                    days < 7 -> "${days}天前"
                    days < 30 -> "${days / 7}週前"
                    days < 365 -> "${days / 30}個月前"
                    else -> "${days / 365}年前"
                }
                lang.startsWith("ko") -> when {
                    days < 1 -> "오늘"
                    days < 7 -> "${days}일 전"
                    days < 30 -> "${days / 7}주 전"
                    days < 365 -> "${days / 30}개월 전"
                    else -> "${days / 365}년 전"
                }
                lang.startsWith("ja") -> when {
                    days < 1 -> "今日"
                    days < 7 -> "${days}日前"
                    days < 30 -> "${days / 7}週間前"
                    days < 365 -> "${days / 30}か月前"
                    else -> "${days / 365}年前"
                }
                else -> when {
                    days < 1 -> "today"
                    days < 2 -> "yesterday"
                    days < 7 -> "$days days ago"
                    days < 30 -> "${days / 7} weeks ago"
                    days < 365 -> "${days / 30} months ago"
                    else -> "${days / 365} years ago"
                }
            }
        } catch (_: Exception) { return "" }
    }



    private fun addSearchResult(
        searchResult: com.liskovsoft.youtubeapi.search.models.SearchResult?,
        title: String, groupType: Int,
        result: MutableList<MediaGroup?>, seenIds: MutableSet<String>, excluded: Set<String>
    ) {
        searchResult?.let {
            val groups = YouTubeMediaGroup.from(it, groupType)
            groups?.firstOrNull()?.let { group ->
                (group as? YouTubeMediaGroup)?.title = title
                group.mediaItems?.removeAll { item ->
                    val id = item?.videoId ?: return@removeAll false
                    !seenIds.add(id) || excluded.contains(id)
                }
                if (group.isEmpty != true) result.add(group)
            }
        }
    }

    /**
     * Sequential search fallback (used by prefetch and trending where parallelism is unnecessary).
     */
    fun getSearchFallback(queries: List<Pair<String, String>>, groupType: Int): List<MediaGroup?> {
        val result = mutableListOf<MediaGroup?>()
        val seenIds = mutableSetOf<String>()
        val excluded = excludedVideoIds
        for ((title, query) in queries) {
            val tq = System.currentTimeMillis()
            val searchResult = searchWithVisitorData(query)
            System.err.println("[PERF]search '$query' took ${System.currentTimeMillis() - tq}ms")
            searchResult?.let {
                val groups = YouTubeMediaGroup.from(it, groupType)
                groups?.firstOrNull()?.let { group ->
                    (group as? YouTubeMediaGroup)?.title = title
                    group.mediaItems?.removeAll { item ->
                        val id = item?.videoId ?: return@removeAll false
                        !seenIds.add(id) || excluded.contains(id)
                    }
                    if (group.isEmpty != true) result.add(group)
                }
            }
        }
        return result
    }

    companion object {
        /**
         * Localized search queries, set by the app layer (BrowsePresenter)
         * from Android string resources. Each pair is (displayTitle, searchQuery).
         * Populated before home/trending loads. Falls back to English if not set.
         */
        @JvmStatic @Volatile
        var homeQueries: List<Pair<String, String>> = listOf(
            "Trending" to "popular music video",
            "Popular" to "most viewed video",
            "For You" to "best videos this week"
        )

        @JvmStatic @Volatile
        var trendingQueries: List<Pair<String, String>> = listOf(
            "Trending Now" to "trending music video",
            "Viral" to "viral video this week",
            "Charts" to "music chart"
        )

        /**
         * Video IDs to exclude from search fallback results (e.g. already watched).
         * Populated by the app layer (BrowsePresenter) before requesting home/trending.
         * Thread-safe: replaced atomically, read during search fallback.
         */
        @JvmStatic @Volatile
        var excludedVideoIds: Set<String> = emptySet()

        /** Kworb trending section title, set from string resources by app layer */
        @JvmStatic @Volatile
        var kworbTitle: String = "Trending Now"

        /** Increments on each home refresh to rotate query pools */
        @JvmStatic
        val refreshCounter = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Multiple query pools for rotation. Set by app layer from string resources.
         * Each pool is a List of (title, query) pairs used for one refresh cycle.
         */
        @JvmStatic @Volatile
        var homeQueryPools: List<List<Pair<String, String>>> = emptyList()

        /**
         * Prefetched video cache for next home refresh.
         * Background search fills this while user watches a video.
         * On next home load, these are shown first (fresher content).
         */
        @JvmStatic
        val prefetchedGroups: MutableList<MediaGroup> = java.util.concurrent.CopyOnWriteArrayList()

        /** Channel IDs already in prefetch — limits same-creator repetition */
        @JvmStatic
        val prefetchedChannelIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Video IDs already shown or prefetched — prevents duplicates across refreshes */
        @JvmStatic
        val shownVideoIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        @JvmStatic
        fun clearPrefetchCache() {
            prefetchedGroups.clear()
            prefetchedChannelIds.clear()
            // Don't clear shownVideoIds — persists across refreshes to avoid repeats
        }
    }

    /**
     * Background prefetch: search for fresh videos and cache them.
     * Called during video playback to prepare content for next home refresh.
     * Limits same-creator repetition (max 2 videos per channel across prefetch).
     */
    fun prefetchForHome(queries: List<Pair<String, String>>) {
        val maxPerChannel = 2
        for ((title, query) in queries) {
            val searchResult = searchWithVisitorData(query) ?: continue

            val groups = YouTubeMediaGroup.from(searchResult, MediaGroup.TYPE_HOME) ?: continue
            val group = groups.firstOrNull() ?: continue
            (group as? YouTubeMediaGroup)?.title = "$title ✦"

            // Filter: no dupes, no watched, limit per creator
            group.mediaItems?.removeAll { item ->
                val id = item?.videoId ?: return@removeAll true
                val channelId = item.channelId ?: ""
                val excluded = excludedVideoIds
                // Skip if already shown, watched, or too many from same channel
                shownVideoIds.contains(id) ||
                excluded.contains(id) ||
                (channelId.isNotEmpty() &&
                    java.util.Collections.frequency(prefetchedChannelIds.toList(), channelId) >= maxPerChannel &&
                    !prefetchedChannelIds.add(channelId + "_skip")) ||
                !shownVideoIds.add(id).also { if (it && channelId.isNotEmpty()) prefetchedChannelIds.add(channelId) }
            }

            if (group.isEmpty != true) {
                prefetchedGroups.add(group)
            }
        }
    }

    fun getSports(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getSportsQuery, MediaGroup.TYPE_SPORTS)
    }

    fun getLive(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getLiveQuery, MediaGroup.TYPE_LIVE)
    }

    fun getMyVideos(): MediaGroup? {
        return getBrowseGridTV(BrowseApiHelper::getMyVideosQuery, MediaGroup.TYPE_MY_VIDEOS)
    }

    fun getMovies(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getMoviesQuery, MediaGroup.TYPE_MOVIES)
    }

    fun getKidsHome(): List<MediaGroup?>? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_KIDS_HOME)
        val kidsResult = mBrowseApi.getBrowseResultKids(BrowseApiHelper.getKidsHomeQuery())

        return RetrofitHelper.get(kidsResult)?.let {
            val result = mutableListOf<MediaGroup?>()
            it.getRootSection()?.let { result.add(KidsSectionMediaGroup(it, options)) }
            it.getSections()?.forEach {
                if (it?.getItems() == null && it?.getParams() != null) {
                    val kidsResultNested = mBrowseApi.getBrowseResultKids(BrowseApiHelper.getKidsHomeQuery(it.getParams()!!))
                    RetrofitHelper.get(kidsResultNested)?.getRootSection()?.let {
                        result.add(KidsSectionMediaGroup(it, options))
                    }
                }
            }

            result
        }
    }

    open fun getSubscriptions(): MediaGroup? {
        return getSubscriptionsTV()
    }

    private fun getSubscriptionsWeb(): MediaGroup? {
        val browseResult = mBrowseApi.getBrowseResult(BrowseApiHelper.getSubscriptionsQuery(AppClient.WEB))

        return RetrofitHelper.get(browseResult)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_SUBSCRIPTIONS)) }
    }

    private fun getSubscriptionsTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_SUBSCRIPTIONS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            // Prepare to move LIVE items to the top. Multiple results should be combined first.
            val (overrideItems, overrideKey) = continueIfNeededTV(it.getItems(), it.getContinuationToken(), options)

            BrowseMediaGroupTV(it, options, overrideItems = overrideItems, overrideKey = overrideKey)
        }
    }

    open fun getSubscribedChannels(): MediaGroup? {
        return getSubscribedChannelsTV() ?: getSubscribedChannelsWeb()
    }

    private fun getSubscribedChannelsWeb(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val guideResult = mBrowseApi.getGuideResult(PostDataHelper.createQueryWeb(""))

        return RetrofitHelper.get(guideResult)?.let { GuideMediaGroup(it, options) }
    }

    private fun getSubscribedChannelsTV(sortByName: Boolean = false): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let { it.getTabs()?.let { ChannelListMediaGroup(it, options, if (sortByName) SORT_BY_NAME else SORT_DEFAULT) } }
    }

    open fun getSubscribedChannelsByName(): MediaGroup? {
        return getSubscribedChannelsTV(sortByName = true) ?: getSubscribedChannelsByNameWeb()
    }

    private fun getSubscribedChannelsByNameWeb(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val guideResult = mBrowseApi.getGuideResult(PostDataHelper.createQueryWeb(""))

        return RetrofitHelper.get(guideResult)?.let { GuideMediaGroup(it, options, SORT_BY_NAME) }
    }

    open fun getSubscribedChannelsByNewContent(): MediaGroup? {
        return getSubscribedChannelsByNewContentTV()
    }

    private fun getSubscribedChannelsByNewContentTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let { it.getTabs()?.let { ChannelListMediaGroup(it, options, SORT_BY_NEW_CONTENT) } }
    }

    fun getShorts(): MediaGroup? {
        return getShortsTV() ?: getShortsWeb()
    }

    //fun getShorts(): MediaGroup? {
    //    return getShortsTV()
    //}
    //
    //fun getShorts2(): MediaGroup? {
    //    return getShortsWeb()
    //}

    private fun getShortsWeb(auth: Boolean = false): MediaGroup? {
        val firstResult = mBrowseApi.getReelResult(BrowseApiHelper.getReelQuery())

        return RetrofitHelper.get(firstResult, auth) ?.let { firstItem ->
            val result = continueShortsWeb(firstItem.getContinuationToken(), auth)
            result?.mediaItems?.add(0, ShortsMediaItem(null, firstItem))

            if (auth)
                getSubscribedShortsWeb()?.let { result?.mediaItems?.addAll(0, it) }

            return result
        }
    }

    private fun getShortsTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_SHORTS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            it.getShortItems()?.let { SubscribedShortsMediaGroup(it) }
        }
    }

    fun getMusic(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getMusicQuery, MediaGroup.TYPE_MUSIC)
    }

    fun getLikedMusic(): MediaGroup? {
        return getLikedMusicTV() ?: getLikedMusicWeb()
    }

    fun getNews(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getNewsQuery, MediaGroup.TYPE_NEWS)
    }

    fun getGaming(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getGamingQuery, MediaGroup.TYPE_GAMING)
    }

    fun getHistory(): MediaGroup? {
        return getBrowseGridTV(BrowseApiHelper::getMyHistoryQuery, MediaGroup.TYPE_HISTORY)
    }

    private fun getLikedMusicWeb(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)
        val result = mBrowseApi.getBrowseResult(BrowseApiHelper.getLikedMusicQuery(AppClient.WEB))

        return RetrofitHelper.get(result)?.let { BrowseMediaGroup(it, options) }
    }

    private fun getLikedMusicTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)
        val result = mBrowseApi.getContinuationResultTV(BrowseApiHelper.getLikedMusicContinuation(options.clientTV))

        return RetrofitHelper.get(result)?.let { WatchNexContinuationMediaGroup(it, options) }
    }

    fun getNewMusicAlbums(): MediaGroup? {
        val result = mBrowseApi.getBrowseResult(BrowseApiHelper.getNewMusicAlbumsQuery())

        return RetrofitHelper.get(result, false)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)) }
    }

    fun getNewMusicVideos(): MediaGroup? {
        val result = mBrowseApi.getBrowseResult(BrowseApiHelper.getNewMusicVideosQuery())

        return RetrofitHelper.get(result, false)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)) }
    }

    open fun getMyPlaylists(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_USER_PLAYLISTS)
        val result = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getMyPlaylistQuery(options.clientTV))

        return RetrofitHelper.get(result)?.let {
            if (it.getItems()?.firstOrNull { it?.getPlaylistId().equals(BrowseApiHelper.WATCH_LATER_PLAYLIST) } != null) {
                BrowseMediaGroupTV(it, options)
            } else { // No Watch Later (moved to the dedicated subsection)
                val library = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getMyLibraryQuery(options.clientTV))

                val outer = it

                RetrofitHelper.get(library)?.let {
                    val watchLater = it.getItems()?.getOrNull(1) // Watch Later subsection
                    BrowseMediaGroupTV(outer, options, watchLater?.let { outer.getItems()?.toMutableList()?.apply { add(0, it) } })
                }
            }
        }
    }

    private fun continueShortsWeb(continuationKey: String?, auth: Boolean = false): MediaGroup? {
        if (continuationKey == null) {
            return null
        }

        val continuation = mBrowseApi?.getReelContinuationResult(BrowseApiHelper.getReelContinuationQuery(AppClient.WEB, continuationKey))

        return RetrofitHelper.get(continuation, auth)?.let {
            val result = mutableListOf<MediaItem?>()

            it.getItems()?.forEach {
                if (it?.videoId != null && it.params != null) {
                    val details = mBrowseApi?.getReelResult(BrowseApiHelper.getReelDetailsQuery(AppClient.WEB, it.videoId, it.params))

                    RetrofitHelper.get(details, auth)?.let {
                            info -> result.add(ShortsMediaItem(it, info))
                    }
                }
            }

            ShortsMediaGroup(result, it.getContinuationToken(), MediaGroupOptions.create(MediaGroup.TYPE_SHORTS))
        }
    }

    open fun getChannelAsGrid(channelId: String?): MediaGroup? {
        return getChannelVideosTV(channelId) ?: getChannelVideosWeb(channelId)
    }

    private fun getChannelVideosTV(channelId: String?): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val rows = getBrowseRowsTV({ BrowseApiHelper.getChannelVideosQuery(it, channelId) }, MediaGroup.TYPE_CHANNEL_UPLOADS)?.first
        val firstRow = rows?.firstOrNull()
        return firstRow?.let { if (it.nextPageKey != null) it else
            MergedMediaGroup(MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS), rows.getOrNull(1), it)
        }
    }

    private fun getChannelVideosWeb(channelId: String?, auth: Boolean = false): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val videos = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelVideosQuery(AppClient.WEB, channelId))
        val live = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelLiveQuery(AppClient.WEB, channelId))

        RetrofitHelper.get(videos, auth)?.let { return BrowseMediaGroup(it, options, RetrofitHelper.get(live)) }

        RetrofitHelper.get(live, auth)?.let { return LiveMediaGroup(it, options) }

        return null
    }

    fun getChannelAsGridOld(channelId: String?): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val videos = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelVideosQuery(AppClient.WEB, channelId))

        return RetrofitHelper.get(videos)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)) }
    }

    fun getChannelLive(channelId: String?): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val live = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelLiveQuery(AppClient.WEB, channelId))

        return RetrofitHelper.get(live)?.let { LiveMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)) }
    }

    fun getChannelSearch(channelId: String?, query: String?): MediaGroup? {
        return getChannelSearchWeb(channelId, query)
    }

    private fun getChannelSearchWeb(channelId: String?, query: String?, auth: Boolean = false): MediaGroup? {
        if (channelId == null || query == null) {
            return null
        }

        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val search = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelSearchQuery(AppClient.WEB, channelId, query))

        return RetrofitHelper.get(search, auth)?.let { BrowseMediaGroup(it, options) }
    }

    /**
     * Latest, Popular, Oldest
     */
    fun getChannelSortingOptions(channelId: String?): List<MediaGroup?>? {
        return getChannelSortingOptionsWeb(channelId)
    }

    /**
     * Latest, Popular, Oldest
     */
    private fun getChannelSortingOptionsWeb(channelId: String?, auth: Boolean = false): List<MediaGroup?>? {
        if (channelId == null) {
            return null
        }

        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val videos = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelVideosQuery(AppClient.WEB, channelId))

        return RetrofitHelper.get(videos, auth)?.let {
            it.getChips()?.mapNotNull { if (it != null) ChipMediaGroup(it, options) else null }
        }
    }

    open fun getChannel(channelId: String?, params: String?): Pair<List<MediaGroup?>?, String?>? {
        return getChannelTV(channelId, params) ?: getChannelWeb(channelId)?.let { Pair(it, null) }
    }

    private fun getChannelWeb(channelId: String?, auth: Boolean = false): List<MediaGroup?>? {
        if (channelId == null) {
            return null
        }

        val channelOptions = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL, channelId)
        val uploadOptions = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS, channelId)
        val result = mutableListOf<MediaGroup>()

        val homeResult = getBrowseRedirect(channelId) {
            val home = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelHomeQuery(AppClient.WEB, it))
            RetrofitHelper.get(home, auth)
        }

        var shortTab: MediaGroup? = null

        homeResult?.let { it.getTabs()?.drop(1)?.forEach { // skip first tab - Home (repeats Videos)
            if (it?.title?.contains("Shorts") == true) { // move Shorts tab lower
                shortTab = TabMediaGroup(it, channelOptions)
                return@forEach
            }
            val title = it?.getTitle()
            if (title != null && result.firstOrNull { it.title == title } == null) // only unique rows
                result.add(TabMediaGroup(it, channelOptions)) } }

        shortTab?.let { result.add(it) } // move Shorts tab lower

        homeResult?.let { it.getNestedShelves()?.forEach {
            val title = it?.getTitle()
            if (it != null && result.firstOrNull { it.title == title } == null) // only unique rows
                result.add(ItemSectionMediaGroup(it, if (title == null) uploadOptions else channelOptions)) } } // playlists don't have a title

        if (result.isEmpty()) {
            val playlist = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelQuery(AppClient.WEB, channelId))
            RetrofitHelper.get(playlist, auth)?.let {
                if (it.getTitle() != null) result.add(BrowseMediaGroup(it, uploadOptions))
            }
        }

        //if (result.isEmpty()) {
        //    getChannelResult(AppClient.WEB_REMIX, channelId)?.let {
        //        if (it.getTitle() != null) result.add(BrowseMediaGroup(it, MediaGroupOptions.createOptions(MediaGroup.TYPE_CHANNEL_UPLOADS)))
        //    }
        //}

        return result.ifEmpty { null }
    }

    private fun getChannelTV(channelId: String?, params: String?): Pair<List<MediaGroup?>?, String?>? {
        if (channelId == null) {
            return null
        }

        return getBrowseRowsTV({ BrowseApiHelper.getChannelQuery(it, channelId, params) }, MediaGroup.TYPE_CHANNEL, MediaGroup.TYPE_CHANNEL_UPLOADS)
    }

    /**
     * A special type of a channel that could be found inside Music section (see Liked row More button)
     */
    fun getGridChannel(channelId: String, params: String? = null): MediaGroup? {
        return getBrowseGridTV({ BrowseApiHelper.getChannelQuery(it, channelId, params) }, MediaGroup.TYPE_CHANNEL_UPLOADS)
    }

    open fun getGroup(reloadPageKey: String, type: Int, title: String?): MediaGroup? {
        return continueGroupTV(EmptyMediaGroup(reloadPageKey, type, title), true)
    }

    fun continueGroup(group: MediaGroup?): MediaGroup? {
        return when (group) {
            is ShortsMediaGroup -> continueShortsWeb(group.nextPageKey)
            is ShelfSectionMediaGroup -> continueGroupTV(group)
            is BrowseMediaGroupTV, is MergedMediaGroup -> continueGroupTV(group)
            is WatchNexContinuationMediaGroup -> continueGroupTV(group)
            else -> continueGroupWeb(group)?.firstOrNull()
        }
    }

    fun continueEmptyGroup(group: MediaGroup?): List<MediaGroup?>? {
        if (group?.nextPageKey != null) {
            return continueGroupTV(group)?.let { listOf(it) } ?: continueGroupWeb(group)
        } else if (group?.channelId != null) {
            return continueTabWeb(group)?.let { listOf(it) }
        }

        return null
    }

    fun continueSectionList(nextPageKey: String?, groupType: Int): Pair<List<MediaGroup?>?, String?>? {
        return continueSectionListTV(nextPageKey, groupType)
    }

    private fun continueSectionListTV(nextPageKey: String?, groupType: Int): Pair<List<MediaGroup?>?, String?>? {
        if (nextPageKey == null) {
            return null
        }

        val options = MediaGroupOptions.create(groupType)
        val continuationResult =
            mBrowseApi.getContinuationResultTV(BrowseApiHelper.getContinuationQuery(options.clientTV, nextPageKey))

        return RetrofitHelper.get(continuationResult)?.let {
            val result = mutableListOf<MediaGroup?>()
            it.getShelves()?.forEach { if (it != null) addOrMerge(result, ShelfSectionMediaGroup(it, options)) }
            Pair(result, it.getContinuationToken())
        }
    }

    private fun continueTabWeb(group: MediaGroup?, auth: Boolean = false): MediaGroup? {
        if (group?.channelId == null) {
            return null
        }

        val options = MediaGroupOptions.create(group.type)
        val browseResult =
            mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelQuery(AppClient.WEB, group.channelId, group.params))

        return RetrofitHelper.get(browseResult, auth)?.let { BrowseMediaGroup(it, options).apply { title = group.title } }
    }

    /**
     * NOTE: Can continue Chip or Group
     */
    private fun continueGroupWeb(group: MediaGroup?, auth: Boolean = false): List<MediaGroup?>? {
        if (group?.nextPageKey == null) {
            return null
        }

        val options = MediaGroupOptions.create(group.type)
        val continuationResult =
            mBrowseApi.getContinuationResult(BrowseApiHelper.getContinuationQuery(AppClient.WEB, group.nextPageKey))

        return RetrofitHelper.get(continuationResult, auth)?.let {
            val result = mutableListOf<MediaGroup?>()

            result.add(ContinuationMediaGroup(it, options).apply { title = group.title })
            it.getSections()?.forEach { if (it != null) result.add(RichSectionMediaGroup(it, options)) }

            result
        }
    }

    private fun continueGroupTV(group: MediaGroup?, continueIfNeeded: Boolean = false): MediaGroup? {
        if (group?.nextPageKey == null) {
            return null
        }

        val options = MediaGroupOptions.create(group.type)
        val continuationResult =
            mBrowseApi.getContinuationResultTV(BrowseApiHelper.getContinuationQuery(options.clientTV, group.nextPageKey))

        return RetrofitHelper.get(continuationResult)?.let {
            // Prepare to move LIVE items to the top. Multiple results should be combined first.
            val (overrideItems, overrideKey) = if (continueIfNeeded) continueIfNeededTV(it.getItems(), it.getContinuationToken(), options) else Pair(null, null)

            WatchNexContinuationMediaGroup(it, options, overrideItems = overrideItems, overrideKey = overrideKey).apply { title = group.title }
        }
    }

    private fun getBrowseRowsWeb(query: String, sectionType: Int, auth: Boolean = false): List<MediaGroup?>? {
        val options = MediaGroupOptions.create(sectionType)
        val browseResult = mBrowseApi.getBrowseResult(query)

        return RetrofitHelper.get(browseResult, auth)?.let {
            val result = mutableListOf<MediaGroup?>()

            // First chip is always empty and corresponds to current result.
            // Also title used as id in continuation. No good.
            // NOTE: First tab on home page has no title.
            result.add(BrowseMediaGroup(it, MediaGroupOptions.create(sectionType))) // always renders first tab
            it.getTabs()?.drop(1)?.forEach { if (it?.getTitle() != null) result.add(TabMediaGroup(it, options)) }
            it.getSections()?.forEach { if (it?.getTitle() != null) addOrMerge(result, RichSectionMediaGroup(it, options)) }
            it.getChips()?.forEach { if (it?.getTitle() != null) result.add(ChipMediaGroup(it, options)) }

            result
        }
    }

    private fun getBrowseRowsTV(query: (AppClient) -> String, sectionType: Int, gridType: Int = MediaGroup.TYPE_UNDEFINED): Pair<List<MediaGroup?>?, String?>? {
        val rowsOptions = MediaGroupOptions.create(sectionType)
        val gridOptions = MediaGroupOptions.create(gridType)
        val browseResult = mBrowseApi.getBrowseResultTV(query(rowsOptions.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            val result = mutableListOf<MediaGroup?>()
            it.getShelves()?.forEach { if (it != null) addOrMerge(result, ShelfSectionMediaGroup(it, rowsOptions)) }

            if (result.isEmpty()) // playlist
                addOrMerge(result, BrowseMediaGroupTV(it, gridOptions))

            Pair(result, it.getContinuationToken())
        }
    }

    private fun getBrowseGridTV(query: (AppClient) -> String, sectionType: Int, shouldContinue: Boolean = false): MediaGroup? {
        val options = MediaGroupOptions.create(sectionType)
        val browseResult = mBrowseApi.getBrowseResultTV(query(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            // Prepare to move LIVE items to the top. Multiple results should be combined first.
            var continuation: Pair<List<ItemWrapper?>?, String?>? = null
            if (shouldContinue) {
                continuation = continueIfNeededTV(it.getItems(), it.getContinuationToken(), options)
            }

            BrowseMediaGroupTV(it, options, overrideItems = continuation?.first, overrideKey = continuation?.second)
        }
    }

    private fun addOrMerge(result: MutableList<MediaGroup?>, group: MediaGroup) {
        // Always add, merging will be done later
        result.add(group)
    }

    private fun getRecommendedWeb(): List<MediaGroup?>? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_HOME)
        val guideResult = mBrowseApi.getGuideResult(PostDataHelper.createQueryWeb(""))

        return RetrofitHelper.get(guideResult)?.let {
            val result = mutableListOf<MediaGroup?>()

            it.getRecommended()?.forEach { if (it != null) result.add(RecommendedMediaGroup(it, options)) }

            result
        }
    }

    private fun getSubscribedShortsWeb(): List<MediaItem?>? {
        val browseResult = mBrowseApi.getBrowseResult(BrowseApiHelper.getSubscriptionsQuery(AppClient.WEB))

        return RetrofitHelper.get(browseResult)?.let { it.getShortItems()?.let { SubscribedShortsMediaGroup(it) } }?.mediaItems
    }

    private fun getBrowseRedirect(browseId: String, browseExpression: (String) -> BrowseResult?): BrowseResult? {
        val result = browseExpression(browseId)
        return if (result?.getRedirectBrowseId() != null) browseExpression(result.getRedirectBrowseId()!!) else result
    }

    private fun continueIfNeededTV(items: List<ItemWrapper?>?, continuationKey: String?, options: MediaGroupOptions): Pair<List<ItemWrapper?>?, String?> {
        var combinedItems: List<ItemWrapper?>? = items
        var combinedKey: String? = continuationKey
        for (i in 0 until 10) {
            // NOTE: bigger max value help moving live videos to the top (e.g. sorting)
            if (combinedKey == null || (combinedItems?.size ?: 0) > 60)
                break

            val result =
                mBrowseApi.getContinuationResultTV(BrowseApiHelper.getContinuationQuery(options.clientTV, combinedKey))

            combinedKey = null

            RetrofitHelper.get(result)?.let {
                combinedItems = (combinedItems ?: emptyList()) + (it.getItems() ?: emptyList())
                combinedKey = it.getContinuationToken()
            }
        }

        return Pair(combinedItems, combinedKey)
    }
}