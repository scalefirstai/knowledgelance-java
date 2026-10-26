package io.knowledgelake.models;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.knowledgelake.config.Settings;
import io.knowledgelake.models.tokenizer.HfTokenizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Embedding and reranking models behind two small interfaces.
 *
 * <ul>
 *   <li>{@link HashEmbedder}: deterministic feature hashing, no downloads. For tests, CI and
 *       air-gapped smoke runs; it is lexical, not semantic.
 *   <li>{@link OnnxEmbedder} / {@link OnnxReranker}: any Hugging Face repo that ships
 *       {@code tokenizer.json} and an ONNX export. Runs in-process on ONNX Runtime; no text leaves
 *       the host.
 * </ul>
 *
 * Models load from a local directory when {@code model} is a path, so production images bake
 * weights in and run with {@code HF_HUB_OFFLINE=1}.
 */
public final class Models {
    private Models() {}

    public interface Embedder {
        String name();

        int dim();

        float[][] embedDocuments(List<String> texts);

        float[] embedQuery(String text);
    }

    public interface Reranker {
        String name();

        float[] score(String query, List<String> passages);
    }

    public static Embedder loadEmbedder(Settings.Embedding cfg, String cacheDir) {
        return "onnx".equals(cfg.provider) ? new OnnxEmbedder(cfg, cacheDir) : new HashEmbedder(cfg.dim);
    }

    public static Optional<Reranker> loadReranker(Settings.Rerank cfg, String cacheDir) {
        return "onnx".equals(cfg.provider) ? Optional.of(new OnnxReranker(cfg, cacheDir)) : Optional.empty();
    }

    static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        float inv = (float) (1.0 / Math.max(Math.sqrt(n), 1e-12));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return v;
    }

    // ===================================================================== hash

    public static final class HashEmbedder implements Embedder {
        private final int dim;

        public HashEmbedder(int dim) {
            this.dim = dim;
        }

        @Override
        public String name() {
            return "hash-" + dim;
        }

        @Override
        public int dim() {
            return dim;
        }

        private static List<String> features(String text) {
            List<String> words = new ArrayList<>();
            for (String w : text.toLowerCase().split("[^a-z0-9]+")) if (!w.isEmpty()) words.add(w);
            List<String> feats = new ArrayList<>(words);
            for (int i = 0; i + 1 < words.size(); i++) feats.add(words.get(i) + "_" + words.get(i + 1));
            for (String w : words) { // character trigrams give some robustness to inflection
                String p = "#" + w + "#";
                for (int i = 0; i + 3 <= p.length(); i++) feats.add(p.substring(i, i + 3));
            }
            return feats;
        }

        private float[] embed(String text) {
            float[] v = new float[dim];
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (String f : features(text)) {
                    byte[] d = md.digest(f.getBytes(StandardCharsets.UTF_8));
                    long h = 0;
                    for (int i = 0; i < 8; i++) h |= (d[i] & 0xffL) << (8 * i);
                    v[(int) Long.remainderUnsigned(h, dim)] += (h >>> 63) == 1 ? 1f : -1f;
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return normalize(v);
        }

        @Override
        public float[][] embedDocuments(List<String> texts) {
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < out.length; i++) out[i] = embed(texts.get(i));
            return out;
        }

        @Override
        public float[] embedQuery(String text) {
            return embed(text);
        }
    }

    // ===================================================================== model files

    /**
     * Resolve {@code files} of {@code model} to a local directory: a local path as-is, else the
     * Hugging Face hub cache (shared with Python), else a download into it unless offline.
     */
    static Path resolveModel(String model, List<String> files, String cacheDir) {
        Path local = Path.of(model.replaceFirst("^~", System.getProperty("user.home")));
        if (Files.isDirectory(local)) return local;
        Path hub = cacheDir != null ? Path.of(cacheDir)
                : Path.of(Optional.ofNullable(System.getenv("HF_HOME")).orElse(System.getProperty("user.home") + "/.cache/huggingface"), "hub");
        Path repoDir = hub.resolve("models--" + model.replace("/", "--"));
        Path snapshots = repoDir.resolve("snapshots");
        if (Files.isDirectory(snapshots)) {
            try (Stream<Path> s = Files.list(snapshots)) {
                for (Path snap : s.toList()) {
                    if (files.stream().allMatch(f -> Files.exists(snap.resolve(f)))) return snap;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if ("1".equals(System.getenv("HF_HUB_OFFLINE"))) {
            throw new IllegalStateException("model " + model + " not in cache and HF_HUB_OFFLINE=1");
        }
        Path snap = snapshots.resolve("main");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (String f : files) {
            Path dest = snap.resolve(f);
            if (Files.exists(dest)) continue;
            try {
                Files.createDirectories(dest.getParent());
                var req = HttpRequest.newBuilder(URI.create("https://huggingface.co/" + model + "/resolve/main/" + f));
                String token = System.getenv("HF_TOKEN");
                if (token != null) req.header("Authorization", "Bearer " + token);
                HttpResponse<InputStream> r = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + model + "/" + f);
                Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
                try (InputStream in = r.body()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return snap;
    }

    // ===================================================================== onnx

    abstract static class OnnxBase {
        protected final OrtEnvironment env = OrtEnvironment.getEnvironment();
        protected final OrtSession session;
        protected final HfTokenizer tokenizer;
        protected final boolean wantsTypeIds;

        OnnxBase(String model, String onnxFile, int maxLength, String cacheDir, List<String> providers) {
            Path root = resolveModel(model, List.of("tokenizer.json", onnxFile), cacheDir);
            try {
                tokenizer = HfTokenizer.load(root.resolve("tokenizer.json"), maxLength);
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (providers.contains("CUDAExecutionProvider")) opts.addCUDA(); // GPU optional
                session = env.createSession(root.resolve(onnxFile).toString(), opts);
                wantsTypeIds = session.getInputNames().contains("token_type_ids");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (OrtException e) {
                throw new IllegalStateException("cannot load " + model, e);
            }
        }

        /** Pad a batch and run it; returns the first output and the attention mask. */
        protected Object[] run(List<HfTokenizer.Encoding> batch) throws OrtException {
            int max = batch.stream().mapToInt(HfTokenizer.Encoding::length).max().orElse(0);
            long[][] ids = new long[batch.size()][max];
            long[][] mask = new long[batch.size()][max];
            long[][] types = new long[batch.size()][max];
            for (int i = 0; i < batch.size(); i++) {
                var e = batch.get(i);
                java.util.Arrays.fill(ids[i], tokenizer.padId);
                for (int j = 0; j < e.length(); j++) {
                    ids[i][j] = e.ids()[j];
                    mask[i][j] = 1;
                    types[i][j] = e.typeIds()[j];
                }
            }
            Map<String, OnnxTensor> feeds = new HashMap<>();
            try {
                feeds.put("input_ids", OnnxTensor.createTensor(env, ids));
                feeds.put("attention_mask", OnnxTensor.createTensor(env, mask));
                if (wantsTypeIds) feeds.put("token_type_ids", OnnxTensor.createTensor(env, types));
                try (OrtSession.Result r = session.run(feeds)) {
                    return new Object[] {r.get(0).getValue(), mask};
                }
            } finally {
                feeds.values().forEach(OnnxTensor::close);
            }
        }
    }

    public static final class OnnxEmbedder extends OnnxBase implements Embedder {
        private final Settings.Embedding cfg;

        public OnnxEmbedder(Settings.Embedding cfg, String cacheDir) {
            super(cfg.model, cfg.onnxFile, cfg.maxLength, cacheDir, cfg.providers);
            this.cfg = cfg;
        }

        @Override
        public String name() {
            return cfg.model + ":" + cfg.onnxFile;
        }

        @Override
        public int dim() {
            return cfg.dim;
        }

        @Override
        public float[][] embedDocuments(List<String> texts) {
            float[][] out = new float[texts.size()][];
            for (int start = 0; start < texts.size(); start += cfg.batchSize) {
                List<HfTokenizer.Encoding> batch = new ArrayList<>();
                for (String t : texts.subList(start, Math.min(texts.size(), start + cfg.batchSize))) {
                    batch.add(tokenizer.encode(t));
                }
                try {
                    Object[] res = run(batch);
                    long[][] mask = (long[][]) res[1];
                    for (int i = 0; i < batch.size(); i++) out[start + i] = pool(res[0], mask, i);
                } catch (OrtException e) {
                    throw new IllegalStateException(e);
                }
            }
            for (float[] v : out) {
                if (v.length != cfg.dim) {
                    throw new IllegalStateException("model " + cfg.model + " produced dim " + v.length + ", config says " + cfg.dim);
                }
            }
            return out;
        }

        private float[] pool(Object output, long[][] mask, int row) {
            if (output instanceof float[][] pooled) return normalize(pooled[row].clone()); // already pooled
            float[][] hidden = ((float[][][]) output)[row];
            if ("cls".equals(cfg.pooling)) return normalize(hidden[0].clone());
            float[] v = new float[hidden[0].length];
            int n = 0;
            for (int t = 0; t < hidden.length; t++) {
                if (mask[row][t] == 0) continue;
                n++;
                for (int d = 0; d < v.length; d++) v[d] += hidden[t][d];
            }
            for (int d = 0; d < v.length; d++) v[d] /= Math.max(n, 1);
            return normalize(v);
        }

        @Override
        public float[] embedQuery(String text) {
            return embedDocuments(List.of(cfg.queryPrefix + text))[0];
        }
    }

    public static final class OnnxReranker extends OnnxBase implements Reranker {
        private final Settings.Rerank cfg;

        public OnnxReranker(Settings.Rerank cfg, String cacheDir) {
            super(cfg.model, cfg.onnxFile, cfg.maxLength, cacheDir, cfg.providers);
            this.cfg = cfg;
        }

        @Override
        public String name() {
            return cfg.model + ":" + cfg.onnxFile;
        }

        @Override
        public float[] score(String query, List<String> passages) {
            float[] out = new float[passages.size()];
            for (int start = 0; start < passages.size(); start += cfg.batchSize) {
                List<HfTokenizer.Encoding> batch = new ArrayList<>();
                for (String p : passages.subList(start, Math.min(passages.size(), start + cfg.batchSize))) {
                    batch.add(tokenizer.encodePair(query, p));
                }
                try {
                    float[][] logits = (float[][]) run(batch)[0];
                    for (int i = 0; i < batch.size(); i++) {
                        out[start + i] = (float) (1.0 / (1.0 + Math.exp(-logits[i][0])));
                    }
                } catch (OrtException e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
    }
}
