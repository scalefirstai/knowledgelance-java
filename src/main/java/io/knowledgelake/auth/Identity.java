package io.knowledgelake.auth;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** A caller and the groups their ACL pre-filters are built from. */
public record Identity(String user, Set<String> groups) {
    public Identity {
        groups = Set.copyOf(groups);
    }

    public static Identity of(String user, Collection<String> groups) {
        return new Identity(user, groups.stream().filter(g -> g != null && !g.isBlank()).collect(Collectors.toSet()));
    }
}
