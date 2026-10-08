package com.graphify.indexer.model;

/**
 * A class, method, constructor or field, identified by its spec §4.2 key.
 *
 * @param classFqn    key of the owning type (the type's own key for type symbols)
 * @param memberName  method/field name, {@code <init>} for constructors, {@code null} for types
 * @param parentKey   enclosing type for members and nested types, {@code null} for top-level types
 * @param nameOnly    {@code true} when the symbol is a guess from imports and names, not from a binding
 */
public record Symbol(
        String key,
        SymbolKind kind,
        String classFqn,
        String memberName,
        String displaySignature,
        String parentKey,
        SymbolOrigin origin,
        boolean nameOnly) {
}
