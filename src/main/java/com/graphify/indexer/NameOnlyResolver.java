package com.graphify.indexer;

import java.util.Optional;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;

/** Finds the class a binding-less call, type or annotation refers to. */
final class NameOnlyResolver {

    private final ImportResolver imports;

    NameOnlyResolver(ImportResolver imports) {
        this.imports = imports;
    }

    Optional<String> receiverClass(Expression receiver) {
        if (receiver == null) {
            return Optional.empty();
        }
        ITypeBinding type = receiver.resolveTypeBinding();
        if (type != null && !type.isRecovered()) {
            return Optional.of(SymbolKeys.typeKey(type));
        }
        if (type != null) {
            return imports.resolve(SymbolKeys.spelledName(type.getErasure()));
        }
        if (receiver instanceof SimpleName simple && Character.isLowerCase(simple.getIdentifier().charAt(0))) {
            return Optional.empty();
        }
        if (receiver instanceof Name name) {
            return imports.resolve(name.getFullyQualifiedName());
        }
        return Optional.empty();
    }

    Optional<String> typeClass(Type type) {
        ITypeBinding binding = type.resolveBinding();
        if (binding != null && !binding.isRecovered()) {
            return Optional.of(SymbolKeys.typeKey(binding));
        }
        return typeText(type).flatMap(imports::resolve);
    }

    Optional<String> staticImportClass(String memberName) {
        return imports.staticImportClass(memberName);
    }

    Optional<String> annotationClass(Name typeName) {
        return imports.resolve(typeName.getFullyQualifiedName());
    }

    private static Optional<String> typeText(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            return typeText(parameterized.getType());
        }
        if (type instanceof SimpleType simple) {
            return Optional.of(simple.getName().getFullyQualifiedName());
        }
        return Optional.empty();
    }
}
