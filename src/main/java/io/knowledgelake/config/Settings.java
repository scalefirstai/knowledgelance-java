package io.knowledgelake.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration: one YAML file ({@code kl.yaml}) plus {@code KL_*} environment overrides.
 *
 * <p>The schema is the same as the Python implementation's, so one {@code kl.yaml} configures
 * either. Every setting has a working laptop default: bundles and indexes on local disk, a
 * deterministic hash embedder, no reranker. Fields are public and mutable on purpose; this is a
 * plain data holder bound by Jackson ({@code snake_case} keys).
 */
public class Settings {
    /** ACL entry meaning "every authenticated caller". */
    public static final String PUBLIC = "*";

    public String bundlesUri = "./examples/bundles";
    public String klUri = "./.kl";
    public S3 s3 = new S3();
    public Map<String, Bundle> bundles = new LinkedHashMap<>();
    public Map<String, PartitionConfig> partitions = new LinkedHashMap<>();
    public List<String> globalVolatileFields =
            new ArrayList<>(List.of("exported_at", "export_time", "view_count", "views", "last_viewed", "generated_at"));
    public Embedding embedding = new Embedding();
    public Rerank rerank = new Rerank();
    public Chunking chunking = new Chunking();
    public Ranking ranking = new Ranking();
    public Auth auth = new Auth();
    public Answer answer = new Answer();
    public Events events = new Events();
    public Cache cache = new Cache();
    public String auditPath;
    public String modelCacheDir;

    public static class Bundle {
        /** Used when a concept has no {@code acl}; empty = visible to nobody (fail closed). */
        public List<String> defaultAcl = new ArrayList<>();
        /** Restricted bundles get their own partition (own index, own IAM role). */
        public String partition = "main";
        public List<String> volatileFields = new ArrayList<>();
    }

    public static class PartitionConfig {
        /** Local path or s3:// URI of this partition's index root. */
        public String uri;
        public List<String> requiredGroups = new ArrayList<>();
    }

    public static class Embedding {
        public String provider = "hash"; // hash | onnx
        public String model = "Xenova/bge-small-en-v1.5";
        public String onnxFile = "onnx/model_quantized.onnx";
        public int dim = 384;
        public String pooling = "cls"; // cls | mean
        public int maxLength = 512;
        public String queryPrefix = "Represent this sentence for searching relevant passages: ";
        public int batchSize = 32;
        public List<String> providers = new ArrayList<>(List.of("CPUExecutionProvider"));
        /** Vector field; a model change re-embeds into a new field, then config cuts over. */
        public String column = "vector";
    }

    public static class Rerank {
        public String provider = "none"; // none | onnx
        public String model = "Xenova/ms-marco-MiniLM-L-6-v2";
        public String onnxFile = "onnx/model_quantized.onnx";
        public int maxLength = 512;
        public int topN = 50;
        public int batchSize = 16;
        public List<String> providers = new ArrayList<>(List.of("CPUExecutionProvider"));
    }

    public static class Chunking {
        public int maxTokens = 400;
        public double overlap = 0.15;
    }

    public static class Ranking {
        public int recallK = 100;
        public int rrfK = 60;
        public double wBm25 = 1.0;
        public double wVector = 1.0;
        public double wCard = 0.7;
        public float boostTitle = 3.0f;
        public float boostHeading = 2.0f;
        public double recencyWeight = 0.0;
        public double recencyHalfLifeDays = 180.0;
        public int maxChunksPerConcept = 3;
        public int expandTop = 5;
        public double expandPenalty = 0.5;
        public List<String> identifierPatterns = new ArrayList<>(List.of("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b"));
    }

    public static class Auth {
        public String mode = "none"; // none | header | oidc
        public List<String> anonymousGroups = new ArrayList<>(List.of(PUBLIC));
        public String oidcIssuer;
        public String oidcAudience;
        public String oidcJwksUrl;
        public String groupsClaim = "groups";
        public int groupCacheTtlS = 300;
        public Map<String, List<String>> staticGroups = new HashMap<>();
    }

    public static class Answer {
        public String baseUrl;
        public String model = "llama3.1";
        public int maxContextChunks = 8;
        public double timeoutS = 60.0;
    }

    public static class Events {
        public String sqsQueueUrl;
        public double pollIntervalS = 10.0;
        public String region;
    }

    public static class Cache {
        public String dir;
        public double refreshIntervalS = 15.0;
    }

    public static class S3 {
        public String endpointUrl;
        public String region;
        public String accessKeyId;
        public String secretAccessKey;
        public boolean allowHttp = false;
    }

    public Bundle bundle(String name) {
        Bundle b = bundles.get(name);
        return b != null ? b : new Bundle();
    }

    public String partitionFor(String bundle) {
        return bundle(bundle).partition;
    }

    /** Partition name to index URI; {@code main} always exists under {@code <kl_uri>/lucene/main}. */
    public Map<String, String> partitionUris() {
        Map<String, String> uris = new LinkedHashMap<>();
        uris.put("main", Uris.join(klUri, "lucene", "main"));
        partitions.forEach((name, p) -> uris.put(name, p.uri));
        return uris;
    }

    public List<String> requiredGroups(String partition) {
        PartitionConfig p = partitions.get(partition);
        return p == null ? List.of() : p.requiredGroups;
    }
}
