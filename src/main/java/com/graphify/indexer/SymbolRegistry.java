package com.graphify.indexer;

import com.graphify.indexer.model.Symbol;
import com.graphify.indexer.model.SymbolKind;
import com.graphify.indexer.model.SymbolOrigin;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

/**
 * Deduplicated symbol table for one indexing run. A SOURCE symbol replaces a BINARY one with the same key,
 * and a binding-based symbol replaces a name-only guess.
 */
final class SymbolRegistry {

    private final Map<String, Symbol> symbols = new LinkedHashMap<>();
    private final Set<String> nameOnlyKeys = new HashSet<>();
    private final MethodKeys methodKeys;

    SymbolRegistry() {
        this(new MethodKeys(SourceImports.empty()));
    }

    SymbolRegistry(MethodKeys methodKeys) {
        this.methodKeys = methodKeys;
    }

    String type(ITypeBinding binding) {
        ITypeBinding type = binding.getErasure();
        if (type.isArray()) {
            type = type.getElementType().getErasure();
        }
        if (type.isPrimitive() || type.isNullType()) {
            return null;
        }
        String key = SymbolKeys.typeKey(type);
        if (key.isBlank()) {
            return null;
        }
        String parent = type.getDeclaringClass() == null ? null : type(type.getDeclaringClass());
        putResolved(new Symbol(key, kindOf(type), key, null, type.getName(), parent, originOf(type), false));
        return key;
    }

    String method(IMethodBinding binding) {
        IMethodBinding method = binding.getMethodDeclaration();
        String owner = type(method.getDeclaringClass());
        if (owner == null) {
            return null;
        }
        String key = methodKeys.methodKey(method);
        String ownerName = method.getDeclaringClass().getErasure().getName();
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(t -> t.getErasure().getName())
                .collect(Collectors.joining(", "));
        String display = ownerName + "." + (method.isConstructor() ? ownerName : method.getName()) + "(" + parameters + ")";
        SymbolKind kind = method.isConstructor() ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
        putResolved(new Symbol(key, kind, owner, SymbolKeys.memberName(method), display, owner,
                originOf(method.getDeclaringClass()), false));
        return key;
    }

    String field(IVariableBinding binding) {
        IVariableBinding field = binding.getVariableDeclaration();
        if (field.getDeclaringClass() == null) {
            return null;
        }
        String owner = type(field.getDeclaringClass());
        if (owner == null) {
            return null;
        }
        String key = SymbolKeys.fieldKey(field);
        String display = field.getDeclaringClass().getErasure().getName() + "." + field.getName();
        putResolved(new Symbol(key, SymbolKind.FIELD, owner, field.getName(), display, owner,
                originOf(field.getDeclaringClass()), false));
        return key;
    }

    String nameOnlyType(String classFqn) {
        putNameOnly(new Symbol(classFqn, SymbolKind.CLASS, classFqn, null, simpleName(classFqn), null,
                SymbolOrigin.BINARY, true));
        return classFqn;
    }

    String nameOnlyMethod(String classFqn, String methodName, int argumentCount) {
        String owner = nameOnlyType(classFqn);
        String key = SymbolKeys.nameOnlyMethodKey(classFqn, methodName, argumentCount);
        String display = simpleName(classFqn) + "." + methodName + "(" + argumentCount + " args)";
        putNameOnly(new Symbol(key, SymbolKind.METHOD, owner, methodName, display, owner, SymbolOrigin.BINARY, true));
        return key;
    }

    String nameOnlyConstructor(String classFqn, int argumentCount) {
        String owner = nameOnlyType(classFqn);
        String key = SymbolKeys.nameOnlyConstructorKey(classFqn, argumentCount);
        String display = simpleName(classFqn) + "." + simpleName(classFqn) + "(" + argumentCount + " args)";
        putNameOnly(new Symbol(key, SymbolKind.CONSTRUCTOR, owner, "<init>", display, owner, SymbolOrigin.BINARY, true));
        return key;
    }

    /** Warnings raised while keying methods since the last call; the caller attributes them to the current file. */
    List<String> drainWarnings() {
        return methodKeys.drainWarnings();
    }

    List<Symbol> all() {
        return List.copyOf(symbols.values());
    }

    private void putResolved(Symbol symbol) {
        Symbol existing = symbols.get(symbol.key());
        if (existing == null
                || nameOnlyKeys.remove(symbol.key())
                || (existing.origin() == SymbolOrigin.BINARY && symbol.origin() == SymbolOrigin.SOURCE)) {
            symbols.put(symbol.key(), symbol);
        }
    }

    private void putNameOnly(Symbol symbol) {
        if (symbols.putIfAbsent(symbol.key(), symbol) == null) {
            nameOnlyKeys.add(symbol.key());
        }
    }

    private static SymbolKind kindOf(ITypeBinding type) {
        if (type.isAnnotation()) {
            return SymbolKind.ANNOTATION_TYPE;
        }
        if (type.isInterface()) {
            return SymbolKind.INTERFACE;
        }
        if (type.isEnum()) {
            return SymbolKind.ENUM;
        }
        if (type.isRecord()) {
            return SymbolKind.RECORD;
        }
        return SymbolKind.CLASS;
    }

    private static SymbolOrigin originOf(ITypeBinding type) {
        return type.getErasure().isFromSource() ? SymbolOrigin.SOURCE : SymbolOrigin.BINARY;
    }

    private static String simpleName(String classFqn) {
        String afterPackage = classFqn.substring(classFqn.lastIndexOf('.') + 1);
        return afterPackage.substring(afterPackage.lastIndexOf('$') + 1);
    }
}
