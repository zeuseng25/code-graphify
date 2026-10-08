package com.graphify.indexer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

/**
 * Builds method keys whose parameter types are fully qualified even when a parameter type is missing from the
 * classpath. JDT names such a recovered type by its simple name only, so the name is resolved through the imports
 * of the file that declares the method. Declarations and usages both go through here, so their keys agree.
 */
final class MethodKeys {

    private static final String UNQUALIFIED_PREFIX = "?";

    private final SourceImports sourceImports;
    private final Set<String> warned = new HashSet<>();
    private final List<String> pendingWarnings = new ArrayList<>();

    MethodKeys(SourceImports sourceImports) {
        this.sourceImports = sourceImports;
    }

    String methodKey(IMethodBinding method) {
        IMethodBinding declaration = method.getMethodDeclaration();
        return SymbolKeys.methodKey(declaration, type -> parameterTypeName(type, declaration));
    }

    /** Warnings about parameter types that could not be qualified, since the last call. */
    List<String> drainWarnings() {
        List<String> drained = List.copyOf(pendingWarnings);
        pendingWarnings.clear();
        return drained;
    }

    static boolean hasRecoveredParameter(IMethodBinding method) {
        for (ITypeBinding type : method.getMethodDeclaration().getParameterTypes()) {
            if (isRecovered(type)) {
                return true;
            }
        }
        return false;
    }

    private String parameterTypeName(ITypeBinding type, IMethodBinding declaration) {
        if (!isRecovered(type)) {
            return SymbolKeys.parameterTypeName(type);
        }
        ITypeBinding element = type.getErasure().isArray() ? type.getErasure().getElementType() : type;
        String dimensions = "[]".repeat(type.getErasure().isArray() ? type.getErasure().getDimensions() : 0);
        String name = SymbolKeys.spelledName(element.getErasure());
        Optional<String> qualified = sourceImports.of(SymbolKeys.typeKey(topLevel(declaration.getDeclaringClass())))
                .flatMap(imports -> imports.resolveDeclared(name));
        if (qualified.isPresent()) {
            return qualified.get() + dimensions;
        }
        String fallback = UNQUALIFIED_PREFIX + name + dimensions;
        String owner = SymbolKeys.typeKey(declaration.getDeclaringClass()) + "#" + SymbolKeys.memberName(declaration);
        String warning = "Parameter type " + name + " of " + owner + " is not on the classpath and could not be "
                + "qualified through imports; keyed as " + fallback;
        if (warned.add(warning)) {
            pendingWarnings.add(warning);
        }
        return fallback;
    }

    private static boolean isRecovered(ITypeBinding type) {
        ITypeBinding erasure = type.getErasure();
        return erasure.isRecovered() || (erasure.isArray() && erasure.getElementType().getErasure().isRecovered());
    }

    private static ITypeBinding topLevel(ITypeBinding type) {
        ITypeBinding current = type.getErasure();
        while (current.getDeclaringClass() != null) {
            current = current.getDeclaringClass().getErasure();
        }
        return current;
    }
}
