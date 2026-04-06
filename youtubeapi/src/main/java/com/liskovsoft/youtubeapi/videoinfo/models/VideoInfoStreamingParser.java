package com.liskovsoft.youtubeapi.videoinfo.models;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.RegularVideoFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming JsonReader parser for /player endpoint response.
 * Replaces JsonPath parsing (~2-18s on weak TV CPU) with single-pass scan (~100ms).
 * Only extracts fields needed for playback: streamingData, videoDetails, playabilityStatus, captions.
 */
public class VideoInfoStreamingParser {
    private static final String TAG = "VideoInfoParser";

    public static VideoInfo parse(InputStream is) throws IOException {
        VideoInfo info = new VideoInfo();
        List<AdaptiveVideoFormat> adaptiveFormats = new ArrayList<>();
        List<RegularVideoFormat> regularFormats = new ArrayList<>();
        List<CaptionTrack> captionTracks = new ArrayList<>();

        try (JsonReader r = new JsonReader(new InputStreamReader(is, "UTF-8"))) {
            r.setLenient(true);
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "streamingData": parseStreamingData(r, info, adaptiveFormats, regularFormats); break;
                    case "videoDetails": parseVideoDetails(r, info); break;
                    case "playabilityStatus": parsePlayability(r, info); break;
                    case "captions": parseCaptions(r, captionTracks); break;
                    case "playbackTracking": parseTracking(r, info); break;
                    case "storyboards": parseStoryboards(r, info); break;
                    case "microformat": parseMicroformat(r, info); break;
                    case "playerConfig": parsePlayerConfig(r, info); break;
                    default: r.skipValue(); break;
                }
            }
            r.endObject();
        }

        setField(info, "mAdaptiveFormats", adaptiveFormats.isEmpty() ? null : adaptiveFormats);
        setField(info, "mRegularFormats", regularFormats.isEmpty() ? null : regularFormats);
        setField(info, "mCaptionTracks", captionTracks.isEmpty() ? null : captionTracks);

        return info;
    }

    private static void parseStreamingData(JsonReader r, VideoInfo info,
            List<AdaptiveVideoFormat> adaptive, List<RegularVideoFormat> regular) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "adaptiveFormats": parseFormats(r, adaptive, true); break;
                case "formats": parseFormats(r, regular, false); break;
                case "hlsManifestUrl": info.setHlsManifestUrl(str(r)); break;
                case "dashManifestUrl": info.setDashManifestUrl(str(r)); break;
                case "serverAbrStreamingUrl": setField(info, "mServerAbrStreamingUrl", str(r)); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
    }

    @SuppressWarnings("unchecked")
    private static <T> void parseFormats(JsonReader r, List<T> out, boolean isAdaptive) throws IOException {
        r.beginArray();
        while (r.hasNext()) {
            if (isAdaptive) {
                ((List<AdaptiveVideoFormat>) (List<?>) out).add(parseAdaptiveFormat(r));
            } else {
                ((List<RegularVideoFormat>) (List<?>) out).add(parseRegularFormat(r));
            }
        }
        r.endArray();
    }

    private static AdaptiveVideoFormat parseAdaptiveFormat(JsonReader r) throws IOException {
        AdaptiveVideoFormat f = new AdaptiveVideoFormat();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "itag": f.setITag(r.nextInt()); break;
                case "url": f.setUrl(str(r)); break;
                case "mimeType": f.setMimeType(str(r)); break;
                case "bitrate": f.setBitrate(r.nextInt()); break;
                case "width": f.setWidth(r.nextInt()); break;
                case "height": f.setHeight(r.nextInt()); break;
                case "fps": f.setFps(r.nextInt()); break;
                case "qualityLabel": f.setQualityLabel(str(r)); break;
                case "quality": f.setQuality(str(r)); break;
                case "contentLength": f.setContentLength(str(r)); break;
                case "projectionType": f.setProjectionType(str(r)); break;
                case "audioSampleRate": f.setAudioSamplingRate(str(r)); break;
                case "approxDurationMs": setField(f, "mApproxDurationMs", str(r)); break;
                case "lastModified": setField(f, "mLastModified", str(r)); break;
                case "averageBitrate": setField(f, "mAverageBitrate", intVal(r)); break;
                case "indexRange": f.setIndex(parseRange(r)); break;
                case "initRange": f.setInit(parseRange(r)); break;
                case "signatureCipher": setField(f, "mSignatureCipher", str(r)); break;
                case "cipher": setField(f, "mCipher", str(r)); break;
                case "targetDurationSec": setField(f, "mTargetDurationSec", intVal(r)); break;
                case "maxDvrDurationSec": setField(f, "mMaxDvrDurationSec", intVal(r)); break;
                case "isDrc": setField(f, "mIsDrc", r.nextBoolean()); break;
                case "type": setField(f, "mFormat", str(r)); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        return f;
    }

    private static RegularVideoFormat parseRegularFormat(JsonReader r) throws IOException {
        RegularVideoFormat f = new RegularVideoFormat();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "itag": f.setITag(r.nextInt()); break;
                case "url": f.setUrl(str(r)); break;
                case "mimeType": f.setMimeType(str(r)); break;
                case "bitrate": f.setBitrate(r.nextInt()); break;
                case "width": f.setWidth(r.nextInt()); break;
                case "height": f.setHeight(r.nextInt()); break;
                case "fps": f.setFps(r.nextInt()); break;
                case "qualityLabel": f.setQualityLabel(str(r)); break;
                case "quality": f.setQuality(str(r)); break;
                case "contentLength": f.setContentLength(str(r)); break;
                case "audioSampleRate": f.setAudioSamplingRate(str(r)); break;
                case "approxDurationMs": setField(f, "mApproxDurationMs", str(r)); break;
                case "signatureCipher": setField(f, "mSignatureCipher", str(r)); break;
                case "cipher": setField(f, "mCipher", str(r)); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        return f;
    }

    private static String parseRange(JsonReader r) throws IOException {
        String start = null, end = null;
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "start": start = str(r); break;
                case "end": end = str(r); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        return (start != null && end != null) ? start + "-" + end : null;
    }

    private static void parseVideoDetails(JsonReader r, VideoInfo info) throws IOException {
        VideoDetails vd = new VideoDetails();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "videoId": setField(vd, "mVideoId", str(r)); break;
                case "title": setField(vd, "mTitle", str(r)); break;
                case "author": setField(vd, "mAuthor", str(r)); break;
                case "channelId": setField(vd, "mChannelId", str(r)); break;
                case "lengthSeconds": setField(vd, "mLengthSeconds", str(r)); break;
                case "viewCount": setField(vd, "mViewCount", str(r)); break;
                case "shortDescription": setField(vd, "mShortDescription", str(r)); break;
                case "isLive": setField(vd, "mIsLive", r.nextBoolean()); break;
                case "isLiveContent": setField(vd, "mIsLiveContent", r.nextBoolean()); break;
                case "isLowLatencyLiveStream": setField(vd, "mIsLowLatencyLiveStream", r.nextBoolean()); break;
                case "isUpcoming": setField(vd, "mIsUpcoming", r.nextBoolean()); break;
                case "thumbnail": parseThumbnail(r, vd); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        setField(info, "mVideoDetails", vd);
    }

    private static void parseThumbnail(JsonReader r, VideoDetails vd) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("thumbnails".equals(r.nextName())) {
                List<String> urls = new ArrayList<>();
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("url".equals(r.nextName())) urls.add(str(r));
                        else r.skipValue();
                    }
                    r.endObject();
                }
                r.endArray();
                if (!urls.isEmpty()) setField(vd, "mThumbnailUrl", urls.get(urls.size() - 1));
            } else r.skipValue();
        }
        r.endObject();
    }

    private static void parsePlayability(JsonReader r, VideoInfo info) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "status": setField(info, "mPlayabilityStatus", str(r)); break;
                case "reason": setField(info, "mPlayabilityReason", str(r)); break;
                case "playableInEmbed": setField(info, "mIsPlayableInEmbed", r.nextBoolean()); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
    }

    private static void parseCaptions(JsonReader r, List<CaptionTrack> tracks) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("playerCaptionsTracklistRenderer".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("captionTracks".equals(r.nextName())) {
                        r.beginArray();
                        while (r.hasNext()) {
                            CaptionTrack track = new CaptionTrack();
                            r.beginObject();
                            while (r.hasNext()) {
                                switch (r.nextName()) {
                                    case "baseUrl": setField(track, "mBaseUrl", str(r)); break;
                                    case "languageCode": setField(track, "mLanguageCode", str(r)); break;
                                    case "vssId": setField(track, "mVssId", str(r)); break;
                                    case "name": setField(track, "mName", simpleOrRunText(r)); break;
                                    case "isTranslatable": setField(track, "mIsTranslatable", r.nextBoolean()); break;
                                    default: r.skipValue(); break;
                                }
                            }
                            r.endObject();
                            tracks.add(track);
                        }
                        r.endArray();
                    } else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    private static void parseTracking(JsonReader r, VideoInfo info) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("videostatsPlaybackUrl".equals(name) || "videostatsWatchtimeUrl".equals(name)) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("baseUrl".equals(r.nextName())) {
                        String url = str(r);
                        if ("videostatsPlaybackUrl".equals(name)) setField(info, "mPlaybackUrl", url);
                        else setField(info, "mWatchTimeUrl", url);
                    } else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    private static void parseStoryboards(JsonReader r, VideoInfo info) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("playerStoryboardSpecRenderer".equals(name) || "playerLiveStoryboardSpecRenderer".equals(name)) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("spec".equals(r.nextName())) info.setStoryboardSpec(str(r));
                    else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    private static void parseMicroformat(JsonReader r, VideoInfo info) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("playerMicroformatRenderer".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "uploadDate": setField(info, "mUploadDate", str(r)); break;
                        case "liveBroadcastDetails":
                            r.beginObject();
                            while (r.hasNext()) {
                                if ("startTimestamp".equals(r.nextName())) setField(info, "mStartTimestamp", str(r));
                                else r.skipValue();
                            }
                            r.endObject();
                            break;
                        default: r.skipValue(); break;
                    }
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    private static void parsePlayerConfig(JsonReader r, VideoInfo info) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("audioConfig".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("loudnessDb".equals(r.nextName())) setField(info, "mLoudnessDb", (float) r.nextDouble());
                    else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    // --- Helpers ---
    private static String simpleOrRunText(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.STRING) return r.nextString();
        String result = null;
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if ("simpleText".equals(n)) result = str(r);
            else if ("runs".equals(n)) {
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("text".equals(r.nextName()) && result == null) result = str(r);
                        else r.skipValue();
                    }
                    r.endObject();
                }
                r.endArray();
            } else r.skipValue();
        }
        r.endObject();
        return result;
    }

    private static String str(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NULL) { r.nextNull(); return null; }
        if (t == JsonToken.NUMBER) return String.valueOf(r.nextLong());
        if (t == JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        return r.nextString();
    }

    private static int intVal(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NULL) { r.nextNull(); return 0; }
        if (t == JsonToken.STRING) {
            try { return Integer.parseInt(r.nextString()); } catch (NumberFormatException e) { return 0; }
        }
        return r.nextInt();
    }

    private static void setField(Object target, String fieldName, Object value) {
        if (value == null) return;
        try {
            Field f = findField(target.getClass(), fieldName);
            if (f != null) { f.setAccessible(true); f.set(target, value); }
        } catch (Exception ignored) {}
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try { return cls.getDeclaredField(name); } catch (NoSuchFieldException e) { cls = cls.getSuperclass(); }
        }
        return null;
    }
}
