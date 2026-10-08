package com.graphify.impact;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads HTTP mappings from annotation source text such as {@code @PostMapping(value = "/checkout")}. Spring's
 * {@code *Mapping} annotations put the path in {@code value}/{@code path} or as the single positional value.
 *
 * <p>{@link #path} has three outcomes: a resolved path (the attribute is a pure string literal; only the first
 * literal of an array is reported), an empty string (no path given), or {@link Optional#empty()} (unresolved:
 * a constant, a concatenation or anything else that is not a plain literal). Routes are never guessed.
 */
final class MappingPaths {

    private static final Pattern NAMED_PATH = Pattern.compile("[(,]\\s*(?:value|path)\\s*=(?!=)\\s*");
    private static final Pattern ANY_NAMED = Pattern.compile("^\\s*\\w+\\s*=(?!=)");
    private static final Pattern LITERAL = Pattern.compile("^\\{?\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*[,)}]");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.([A-Z]+)");

    private MappingPaths() {
    }

    static boolean isMapping(String annotationKey) {
        return simpleName(annotationKey).endsWith("Mapping");
    }

    static Optional<String> path(String snippet) {
        int open = snippet == null ? -1 : snippet.indexOf('(');
        if (open < 0) {
            return Optional.of("");
        }
        String args = snippet.substring(open + 1);
        if (args.isBlank() || args.strip().startsWith(")")) {
            return Optional.of("");
        }
        Matcher named = NAMED_PATH.matcher(snippet.substring(open));
        if (named.find()) {
            return literal(snippet.substring(open + named.end()));
        }
        if (ANY_NAMED.matcher(args).find()) {
            return Optional.of("");
        }
        return literal(args.stripLeading());
    }

    private static Optional<String> literal(String expression) {
        Matcher literal = LITERAL.matcher(expression);
        return literal.find() ? Optional.of(literal.group(1)) : Optional.empty();
    }

    static String httpMethod(String annotationKey, String snippet) {
        return switch (simpleName(annotationKey)) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> {
                Matcher method = snippet == null ? null : REQUEST_METHOD.matcher(snippet);
                yield method != null && method.find() ? method.group(1) : null;
            }
        };
    }

    static String join(String prefix, String path) {
        String left = prefix == null ? "" : prefix.strip();
        String right = path == null ? "" : path.strip();
        while (left.endsWith("/")) {
            left = left.substring(0, left.length() - 1);
        }
        if (!right.isEmpty() && !right.startsWith("/")) {
            right = "/" + right;
        }
        String joined = left + right;
        if (joined.isEmpty()) {
            return "/";
        }
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private static String simpleName(String annotationKey) {
        return annotationKey.substring(annotationKey.lastIndexOf('.') + 1);
    }
}
