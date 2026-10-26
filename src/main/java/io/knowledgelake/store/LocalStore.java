package io.knowledgelake.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** A directory on local disk. ETags are content MD5s, matching single-part S3 ETags. */
public final class LocalStore implements ObjectStore {
    private final Path root;

    public LocalStore(String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String root() {
        return root.toString();
    }

    private Path path(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("key escapes store root: " + key);
        return p;
    }

    private static String md5(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<ObjectInfo> list(String prefix) {
        Path start = prefix.isEmpty() ? root : path(prefix);
        List<ObjectInfo> out = new ArrayList<>();
        if (Files.isRegularFile(start)) {
            head(prefix).ifPresent(out::add);
            return out;
        }
        if (!Files.isDirectory(start)) return out;
        try (Stream<Path> walk = Files.walk(start)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        for (Path part : root.relativize(p)) if (part.toString().startsWith(".")) return false;
                        return true;
                    })
                    .sorted()
                    .forEach(p -> out.add(info(root.relativize(p).toString().replace('\\', '/'), p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private ObjectInfo info(String key, Path p) {
        try {
            return new ObjectInfo(key, md5(p), Files.size(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        Path p = path(key);
        return Files.isRegularFile(p) ? Optional.of(info(key, p)) : Optional.empty();
    }

    @Override
    public byte[] getBytes(String key) {
        try {
            return Files.readAllBytes(path(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void putBytes(String key, byte[] data) {
        Path p = path(key);
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
