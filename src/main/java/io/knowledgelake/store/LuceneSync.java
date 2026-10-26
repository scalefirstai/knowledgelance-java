package io.knowledgelake.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes Lucene commits to S3 and mirrors them back to local disk.
 *
 * <p>Lucene files are write-once, so publishing a commit is: upload the files the remote lacks,
 * then write {@code <table>/commit.json} listing exactly the files of that commit. Readers fetch
 * {@code commit.json} first and only then the files it names, so they never see a half-uploaded
 * commit. Files of the previous commit are kept until the next prune, so a pod that read the old
 * {@code commit.json} a moment ago can still finish its download.
 */
public final class LuceneSync {
    private static final Logger log = LoggerFactory.getLogger(LuceneSync.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String COMMIT = "commit.json";
    static final String LOCAL_MARKER = ".kl-commit.json";

    private LuceneSync() {}

    public static void publish(Path localTableDir, ObjectStore remote, String table, boolean prune) throws IOException {
        SegmentInfos infos;
        try (FSDirectory dir = FSDirectory.open(localTableDir)) {
            infos = SegmentInfos.readLatestCommit(dir);
        }
        List<String> files = new ArrayList<>(infos.files(true));
        Map<String, Long> have = new HashMap<>();
        for (var o : remote.list(table + "/")) have.put(o.key().substring(table.length() + 1), o.size());
        int uploaded = 0;
        for (String f : files) {
            long size = Files.size(localTableDir.resolve(f));
            if (have.containsKey(f) && have.get(f) == size) continue;
            remote.putBytes(table + "/" + f, Files.readAllBytes(localTableDir.resolve(f)));
            uploaded++;
        }
        List<String> previous = List.of();
        var prev = remote.head(table + "/" + COMMIT);
        if (prev.isPresent()) {
            JsonNode old = JSON.readTree(remote.getBytes(table + "/" + COMMIT));
            List<String> p = new ArrayList<>();
            old.path("files").forEach(n -> p.add(n.asText()));
            previous = p;
        }
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("generation", infos.getGeneration());
        commit.put("files", files);
        commit.put("previous", previous);
        remote.putBytes(table + "/" + COMMIT, JSON.writeValueAsBytes(commit));
        if (prune) {
            Set<String> keep = new HashSet<>(files);
            keep.addAll(previous);
            keep.add(COMMIT);
            for (String f : have.keySet()) {
                if (!keep.contains(f)) remote.delete(table + "/" + f);
            }
        }
        if (uploaded > 0) log.debug("published {} gen {} ({} files uploaded)", table, infos.getGeneration(), uploaded);
    }

    /** Mirror the latest published commit of {@code table} into {@code localTableDir}. */
    public static boolean pull(ObjectStore remote, String table, Path localTableDir) throws IOException {
        var head = remote.head(table + "/" + COMMIT);
        if (head.isEmpty()) return false;
        byte[] raw = remote.getBytes(table + "/" + COMMIT);
        JsonNode commit = JSON.readTree(raw);
        Path marker = localTableDir.resolve(LOCAL_MARKER);
        if (Files.exists(marker)
                && JSON.readTree(Files.readAllBytes(marker)).path("generation").asLong(-1)
                        == commit.path("generation").asLong()) {
            return false;
        }
        Files.createDirectories(localTableDir);
        Set<String> wanted = new HashSet<>();
        for (JsonNode n : commit.path("files")) wanted.add(n.asText());
        int copied = 0;
        for (String f : wanted) {
            if (f.startsWith("segments_")) continue; // written last, below
            Path dest = localTableDir.resolve(f);
            if (Files.exists(dest)) continue; // write-once: same name, same bytes
            download(remote, table + "/" + f, dest);
            copied++;
        }
        for (String f : wanted) {
            if (f.startsWith("segments_")) download(remote, table + "/" + f, localTableDir.resolve(f));
        }
        try (Stream<Path> s = Files.list(localTableDir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!wanted.contains(n) && !n.equals(LOCAL_MARKER) && !n.equals("write.lock")) Files.deleteIfExists(p);
            }
        }
        Files.write(marker, raw);
        log.debug("pulled {} gen {} ({} files)", table, commit.path("generation").asLong(), copied);
        return true;
    }

    private static void download(ObjectStore remote, String key, Path dest) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        Files.write(tmp, remote.getBytes(key));
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
