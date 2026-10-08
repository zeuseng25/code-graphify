package com.graphify.search;

import com.graphify.common.exception.InvalidRequestException;
import java.util.Locale;

/**
 * What the user typed, split into an optional class FQN (as typed, with {@code $} written as {@code .}; the search
 * compares it case-insensitively), an upper-cased simple class name and an upper-cased member name. A lone word (no
 * {@code #} and no {@code .}) is ambiguous, so it is kept as {@code word} and matches either a type name or a member
 * name. Matching is case-insensitive because people rarely type the exact case of a class they are looking for.
 */
public record SymbolQuery(String qualifiedClass, String simpleClass, String member, String word) {

    public static SymbolQuery parse(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("q must not be blank");
        }
        String query = text.strip();
        int paren = query.indexOf('(');
        if (paren >= 0) {
            query = query.substring(0, paren).strip();
        }
        if (query.indexOf('#') < 0 && query.indexOf('.') < 0 && !query.isEmpty()) {
            return new SymbolQuery(null, null, null, query.toUpperCase(Locale.ROOT));
        }
        String classPart;
        String member;
        int hash = query.indexOf('#');
        if (hash >= 0) {
            classPart = query.substring(0, hash);
            member = query.substring(hash + 1);
        } else {
            int dot = query.lastIndexOf('.');
            String last = query.substring(dot + 1);
            if (!last.isEmpty() && Character.isLowerCase(last.charAt(0))) {
                classPart = dot < 0 ? "" : query.substring(0, dot);
                member = last;
            } else {
                classPart = query;
                member = "";
            }
        }
        classPart = classPart.strip().replace('$', '.');
        member = member.strip();
        if (classPart.isEmpty() && member.isEmpty()) {
            throw new InvalidRequestException("q names no class or member: " + text);
        }
        String simple = classPart.isEmpty() ? null
                : classPart.substring(classPart.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        String qualified = classPart.contains(".") ? classPart : null;
        return new SymbolQuery(qualified, simple, member.isEmpty() ? null : member.toUpperCase(Locale.ROOT), null);
    }
}
