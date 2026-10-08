package com.graphify.repograph;

import java.util.List;
import java.util.Map;

/**
 * A class's members and every usage in the repository from or to the class; {@code externalGroups} maps the
 * external target classes to their groups (empty unless external types were asked for).
 */
public record MemberGraph(List<MemberRef> focusMembers, List<MemberUse> uses,
        Map<String, ExternalGroup> externalGroups) {

    public MemberGraph(List<MemberRef> focusMembers, List<MemberUse> uses) {
        this(focusMembers, uses, Map.of());
    }
}
