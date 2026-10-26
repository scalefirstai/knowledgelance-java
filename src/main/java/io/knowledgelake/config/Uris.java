package io.knowledgelake.config;

/** Helpers for local-path-or-s3:// URIs. */
public final class Uris {
    private Uris() {}

    public static boolean isS3(String uri) {
        return uri != null && uri.startsWith("s3://");
    }

    public static String join(String base, String... parts) {
        StringBuilder sb = new StringBuilder(trimEnd(base));
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            sb.append('/').append(trim(p));
        }
        return sb.toString();
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    private static String trim(String s) {
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }
}
