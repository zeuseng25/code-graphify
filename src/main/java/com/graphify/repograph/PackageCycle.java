package com.graphify.repograph;

import java.util.List;

/** A group of packages that depend on each other in a cycle. */
public record PackageCycle(int id, List<String> packages) {
}
