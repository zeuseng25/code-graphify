package com.graphify.repograph;

/** Levels of the repository graph view (spec §9.1); a graph over the node limit is shown one level up. */
public enum GraphLevel {
    MODULE, PACKAGE, CLASS, METHOD;

    GraphLevel up() {
        return switch (this) {
            case METHOD -> CLASS;
            case CLASS -> PACKAGE;
            case PACKAGE, MODULE -> MODULE;
        };
    }
}
