package com.liskovsoft.youtubeapi.search.fast;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming JSON parser for YouTube search results.
 * Targeted path scan — skips irrelevant branches fast.
 * Supports tileRenderer (new TV 2025+), compactVideoRenderer, videoRenderer.
 */
public final class SearchStreamingParser {

    public static List<VideoItemLite> parse(InputStream inputStream) throws IOException {
        List<VideoItemLite> items = new ArrayList<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(inputStream, "UTF-8"))) {
            r.setLenient(true);
            r.beginObject();
            while (r.hasNext()) {
                if ("contents".equals(r.nextName())) {
                    parseContents(r, items);
                } else { r.skipValue(); }
            }
            r.endObject();
        }
        return items;
    }

    private static void parseContents(JsonReader r, List<VideoItemLite> out) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("sectionListRenderer".equals(name)) {
                parseSectionList(r, out);
            } else if ("twoColumnSearchResultsRenderer".equals(name)) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("primaryContents".equals(r.nextName())) {
                        r.beginObject();
                        while (r.hasNext()) {
                            if ("sectionListRenderer".equals(r.nextName())) parseSectionList(r, out);
                            else r.skipValue();
                        }
                        r.endObject();
                    } else { r.skipValue(); }
                }
                r.endObject();
            } else { r.skipValue(); }
        }
        r.endObject();
    }

    private static void parseSectionList(JsonReader r, List<VideoItemLite> out) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("contents".equals(r.nextName())) {
                r.beginArray();
                while (r.hasNext()) parseSectionItem(r, out);
                r.endArray();
            } else { r.skipValue(); }
        }
        r.endObject();
    }

    private static void parseSectionItem(JsonReader r, List<VideoItemLite> out) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            if ("itemSectionRenderer".equals(name)) parseItemSection(r, out);
            else if ("shelfRenderer".equals(name)) parseShelf(r, out);
            else r.skipValue();
        }
        r.endObject();
    }

    private static void parseItemSection(JsonReader r, List<VideoItemLite> out) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("contents".equals(r.nextName())) {
                r.beginArray();
                while (r.hasNext()) parseRendererWrapper(r, out);
                r.endArray();
            } else { r.skipValue(); }
        }
        r.endObject();
    }

    private static void parseShelf(JsonReader r, List<VideoItemLite> out) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("content".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("horizontalListRenderer".equals(r.nextName())) {
                        r.beginObject();
                        while (r.hasNext()) {
                            if ("items".equals(r.nextName())) {
                                r.beginArray();
                                while (r.hasNext()) parseRendererWrapper(r, out);
                                r.endArray();
                            } else { r.skipValue(); }
                        }
                        r.endObject();
                    } else { r.skipValue(); }
                }
                r.endObject();
            } else { r.skipValue(); }
        }
        r.endObject();
    }

    private static void parseRendererWrapper(JsonReader r, List<VideoItemLite> out) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            String name = r.nextName();
            VideoItemLite item = null;
            switch (name) {
                case "tileRenderer": item = parseTile(r); break;
                case "compactVideoRenderer": case "videoRenderer": item = parseClassic(r); break;
                default: r.skipValue(); break;
            }
            if (item != null && item.videoId != null) out.add(item);
        }
        r.endObject();
    }

    private static VideoItemLite parseClassic(JsonReader r) throws IOException {
        VideoItemLite v = new VideoItemLite();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "videoId": v.videoId = str(r); break;
                case "title": v.title = text(r); break;
                case "longBylineText": case "shortBylineText": case "ownerText":
                    if (v.channelName == null) v.channelName = text(r); else r.skipValue(); break;
                case "publishedTimeText": v.publishedTime = text(r); break;
                case "viewCountText": v.viewCountText = text(r); break;
                case "lengthText": v.lengthText = text(r); break;
                case "thumbnail": v.thumbnailUrl = thumb(r); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        return v;
    }

    private static VideoItemLite parseTile(JsonReader r) throws IOException {
        VideoItemLite v = new VideoItemLite();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "header": parseTileHeader(r, v); break;
                case "metadata": parseTileMetadata(r, v); break;
                case "onSelectCommand": parseTileNav(r, v); break;
                default: r.skipValue(); break;
            }
        }
        r.endObject();
        return v;
    }

    private static void parseTileHeader(JsonReader r, VideoItemLite v) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("tileHeaderRenderer".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("thumbnail".equals(n)) v.thumbnailUrl = thumb(r);
                    else if ("thumbnailOverlays".equals(n)) {
                        r.beginArray();
                        while (r.hasNext()) {
                            r.beginObject();
                            while (r.hasNext()) {
                                if ("thumbnailOverlayTimeStatusRenderer".equals(r.nextName())) {
                                    r.beginObject();
                                    while (r.hasNext()) {
                                        if ("text".equals(r.nextName())) v.lengthText = text(r);
                                        else r.skipValue();
                                    }
                                    r.endObject();
                                } else r.skipValue();
                            }
                            r.endObject();
                        }
                        r.endArray();
                    } else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    private static void parseTileMetadata(JsonReader r, VideoItemLite v) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("tileMetadataRenderer".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("title".equals(n)) v.title = text(r);
                    else if ("lines".equals(n)) parseTileLines(r, v);
                    else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    private static void parseTileLines(JsonReader r, VideoItemLite v) throws IOException {
        int idx = 0;
        r.beginArray();
        while (r.hasNext()) {
            String t = lineText(r);
            if (t != null) {
                if (idx == 0) v.channelName = t;
                else if (idx == 1) v.viewCountText = t;
            }
            idx++;
        }
        r.endArray();
    }

    private static String lineText(JsonReader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        r.beginObject();
        while (r.hasNext()) {
            if ("lineRenderer".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("items".equals(r.nextName())) {
                        r.beginArray();
                        while (r.hasNext()) {
                            r.beginObject();
                            while (r.hasNext()) {
                                if ("lineItemRenderer".equals(r.nextName())) {
                                    r.beginObject();
                                    while (r.hasNext()) {
                                        if ("text".equals(r.nextName())) {
                                            String t = text(r);
                                            if (t != null) sb.append(t);
                                        } else r.skipValue();
                                    }
                                    r.endObject();
                                } else r.skipValue();
                            }
                            r.endObject();
                        }
                        r.endArray();
                    } else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static void parseTileNav(JsonReader r, VideoItemLite v) throws IOException {
        r.beginObject();
        while (r.hasNext()) {
            if ("watchEndpoint".equals(r.nextName())) {
                r.beginObject();
                while (r.hasNext()) {
                    if ("videoId".equals(r.nextName())) v.videoId = str(r);
                    else r.skipValue();
                }
                r.endObject();
            } else r.skipValue();
        }
        r.endObject();
    }

    private static String text(JsonReader r) throws IOException {
        String simple = null, run = null;
        r.beginObject();
        while (r.hasNext()) {
            String n = r.nextName();
            if ("simpleText".equals(n)) simple = str(r);
            else if ("runs".equals(n)) run = firstRun(r);
            else if ("content".equals(n)) simple = str(r);
            else r.skipValue();
        }
        r.endObject();
        return simple != null ? simple : run;
    }

    private static String firstRun(JsonReader r) throws IOException {
        String res = null;
        r.beginArray();
        while (r.hasNext()) {
            r.beginObject();
            while (r.hasNext()) {
                if ("text".equals(r.nextName()) && res == null) res = str(r); else r.skipValue();
            }
            r.endObject();
        }
        r.endArray();
        return res;
    }

    private static String thumb(JsonReader r) throws IOException {
        String last = null;
        r.beginObject();
        while (r.hasNext()) {
            if ("thumbnails".equals(r.nextName())) {
                r.beginArray();
                while (r.hasNext()) {
                    r.beginObject();
                    while (r.hasNext()) {
                        if ("url".equals(r.nextName())) last = str(r); else r.skipValue();
                    }
                    r.endObject();
                }
                r.endArray();
            } else r.skipValue();
        }
        r.endObject();
        return last;
    }

    private static String str(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NULL) { r.nextNull(); return null; }
        if (t == JsonToken.NUMBER) return String.valueOf(r.nextLong());
        if (t == JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        return r.nextString();
    }
}
