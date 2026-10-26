package io.knowledgelake.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.knowledgelake.config.Settings;
import io.knowledgelake.okf.Okf;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Turns a caller into an {@link Identity}. Every front door (REST, SQL, MCP) goes through here.
 *
 * <ul>
 *   <li>{@code none}: development; everyone gets {@code auth.anonymous_groups}.
 *   <li>{@code header}: an authenticating gateway in front sets {@code X-KL-User} /
 *       {@code X-KL-Groups}. Only safe if the gateway strips client-supplied copies.
 *   <li>{@code oidc}: verify a bearer JWT against the IdP's JWKS; groups from a claim.
 * </ul>
 *
 * Group membership is cached for {@code group_cache_ttl_s} (default 5 minutes).
 */
public final class Authenticator {
    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }

    private record Cached(long expiresAt, Object value) {}

    private final Settings.Auth cfg;
    private final Function<String, Set<String>> lookup; // optional directory lookup (Entra ID / Okta)
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile DefaultJWTProcessor<SecurityContext> jwt;

    public Authenticator(Settings.Auth cfg) {
        this(cfg, null);
    }

    public Authenticator(Settings.Auth cfg, Function<String, Set<String>> lookup) {
        this.cfg = cfg;
        this.lookup = lookup;
    }

    private Object cached(String key) {
        Cached c = cache.get(key);
        if (c != null && c.expiresAt() > System.nanoTime()) return c.value();
        cache.remove(key);
        return null;
    }

    private void put(String key, Object value) {
        if (cache.size() > 10_000) cache.clear();
        cache.put(key, new Cached(System.nanoTime() + cfg.groupCacheTtlS * 1_000_000_000L, value));
    }

    @SuppressWarnings("unchecked")
    private Set<String> groupsFor(String user, Set<String> claimed) {
        String key = user + "\u0000" + String.join(",", new java.util.TreeSet<>(claimed));
        Object hit = cached(key);
        if (hit != null) return (Set<String>) hit;
        Set<String> groups = new HashSet<>(claimed);
        groups.addAll(cfg.staticGroups.getOrDefault(user, List.of()));
        if (lookup != null) groups.addAll(lookup.apply(user));
        put(key, Set.copyOf(groups));
        return groups;
    }

    public Identity anonymous() {
        return Identity.of("anonymous", cfg.anonymousGroups);
    }

    /** Trusted in-process callers (CLI, stdio MCP) that already know who they act for. */
    public Identity forUser(String user, Set<String> groups) {
        return Identity.of(user, groupsFor(user, groups));
    }

    /** Headers may be multi-valued; names are matched case-insensitively. */
    public Identity fromHeaders(Map<String, String> headers) {
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.putAll(headers);
        switch (cfg.mode) {
            case "none":
                return anonymous();
            case "header": {
                String user = h.get("x-kl-user");
                if (user == null || user.isBlank()) throw new AuthException("missing X-KL-User header");
                Set<String> claimed = new HashSet<>();
                for (String g : h.getOrDefault("x-kl-groups", "").split(",")) if (!g.isBlank()) claimed.add(g.strip());
                return Identity.of(user, groupsFor(user, claimed));
            }
            case "oidc": {
                String auth = h.getOrDefault("authorization", "");
                if (!auth.toLowerCase(Locale.ROOT).startsWith("bearer ")) throw new AuthException("missing bearer token");
                return fromToken(auth.substring(7).strip());
            }
            default:
                throw new AuthException("unknown auth mode " + cfg.mode);
        }
    }

    public Identity fromToken(String token) {
        String key = "jwt:" + Okf.sha256(token);
        Object hit = cached(key);
        if (hit != null) return (Identity) hit;
        JWTClaimsSet claims;
        try {
            claims = processor().process(token, null);
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("invalid token: " + e.getMessage());
        }
        String user = first(claims, "preferred_username", "email", "sub");
        Object raw = claims.getClaim(cfg.groupsClaim);
        Set<String> claimed = new HashSet<>();
        if (raw instanceof List<?> l) l.forEach(g -> claimed.add(String.valueOf(g)));
        else if (raw != null) claimed.add(raw.toString());
        Identity ident = Identity.of(user, groupsFor(user, claimed));
        put(key, ident);
        return ident;
    }

    private static String first(JWTClaimsSet claims, String... names) {
        for (String n : names) {
            Object v = claims.getClaim(n);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return "unknown";
    }

    private DefaultJWTProcessor<SecurityContext> processor() throws Exception {
        if (jwt != null) return jwt;
        synchronized (this) {
            if (jwt != null) return jwt;
            if (cfg.oidcJwksUrl == null) throw new AuthException("auth.oidc_jwks_url is not configured");
            JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create(cfg.oidcJwksUrl).toURL()).build();
            DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
            p.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256, JWSAlgorithm.PS256), keys));
            JWTClaimsSet.Builder exact = new JWTClaimsSet.Builder();
            if (cfg.oidcIssuer != null) exact.issuer(cfg.oidcIssuer);
            p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    cfg.oidcAudience == null ? null : Set.of(cfg.oidcAudience),
                    exact.build(),
                    new HashSet<>(Arrays.asList("exp", "sub")),
                    null));
            jwt = p;
            return p;
        }
    }
}
