package io.knowledgelake.store;

import io.knowledgelake.config.Settings;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/** An S3 (or S3-compatible) prefix. */
public final class S3Store implements ObjectStore {
    private final String root;
    private final String bucket;
    private final String prefix;
    private final S3Client client;

    public S3Store(String uri, Settings.S3 cfg) {
        this.root = uri.replaceAll("/+$", "");
        String rest = root.substring("s3://".length());
        int slash = rest.indexOf('/');
        this.bucket = slash < 0 ? rest : rest.substring(0, slash);
        this.prefix = slash < 0 ? "" : rest.substring(slash + 1).replaceAll("^/+|/+$", "");
        this.client = client(cfg);
    }

    public static S3Client client(Settings.S3 cfg) {
        S3ClientBuilder b = S3Client.builder().httpClient(UrlConnectionHttpClient.create());
        if (cfg.endpointUrl != null) b.endpointOverride(URI.create(cfg.endpointUrl)).forcePathStyle(true);
        b.region(Region.of(cfg.region != null ? cfg.region : "us-east-1"));
        if (cfg.accessKeyId != null) {
            b.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKeyId, cfg.secretAccessKey)));
        } else {
            b.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return b.build();
    }

    @Override
    public String root() {
        return root;
    }

    private String full(String key) {
        return prefix.isEmpty() ? key : prefix + "/" + key;
    }

    private String rel(String fullKey) {
        return prefix.isEmpty() ? fullKey : fullKey.substring(prefix.length() + 1);
    }

    @Override
    public List<ObjectInfo> list(String p) {
        List<ObjectInfo> out = new ArrayList<>();
        var req = ListObjectsV2Request.builder().bucket(bucket).prefix(full(p)).build();
        for (S3Object o : client.listObjectsV2Paginator(req).contents()) {
            out.add(new ObjectInfo(rel(o.key()), o.eTag().replace("\"", ""), o.size()));
        }
        return out;
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse r = client.headObject(b -> b.bucket(bucket).key(full(key)));
            return Optional.of(new ObjectInfo(key, r.eTag().replace("\"", ""), r.contentLength()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    @Override
    public byte[] getBytes(String key) {
        return client.getObjectAsBytes(b -> b.bucket(bucket).key(full(key))).asByteArray();
    }

    @Override
    public void putBytes(String key, byte[] data) {
        client.putObject(b -> b.bucket(bucket).key(full(key)), RequestBody.fromBytes(data));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(b -> b.bucket(bucket).key(full(key)));
    }

    @Override
    public String keyFromEvent(String fullKey) {
        if (prefix.isEmpty()) return fullKey;
        return fullKey.startsWith(prefix + "/") ? rel(fullKey) : null;
    }
}
