package io.knowledgelake.store;

import io.knowledgelake.config.Settings;
import io.knowledgelake.config.Uris;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Minimal object-store abstraction over local disk and S3 (or anything S3-compatible).
 *
 * <p>Keys are always {@code /}-separated and relative to the store root, so a bundle path such as
 * {@code confluence/spaces/NAV/x.md} is identical on disk and in S3.
 */
public interface ObjectStore {
    record ObjectInfo(String key, String etag, long size) {}

    String root();

    List<ObjectInfo> list(String prefix);

    Optional<ObjectInfo> head(String key);

    byte[] getBytes(String key);

    void putBytes(String key, byte[] data);

    void delete(String key);

    default String getText(String key) {
        return new String(getBytes(key), StandardCharsets.UTF_8);
    }

    /** Map an S3 event key (bucket-relative) back to a store-relative key, or null if outside. */
    default String keyFromEvent(String fullKey) {
        return fullKey;
    }

    static ObjectStore open(String uri, Settings.S3 s3) {
        return Uris.isS3(uri) ? new S3Store(uri, s3) : new LocalStore(uri);
    }
}
