package com.graphify.indexer;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

/** Builds the spec §4.2 symbol keys that link usages to declarations across repositories. */
final class SymbolKeys {

    private SymbolKeys() {
    }

    static String typeKey(ITypeBinding type) {
        ITypeBinding erased = type.getErasure();
        if (erased.isArray()) {
            return typeKey(erased.getElementType());
        }
        String binaryName = erased.getBinaryName();
        return binaryName != null ? binaryName : erased.getQualifiedName();
    }

    /** Correct only when no parameter type is recovered; {@link MethodKeys} qualifies recovered ones. */
    static String methodKey(IMethodBinding method) {
        return methodKey(method.getMethodDeclaration(), SymbolKeys::parameterTypeName);
    }

    static String methodKey(IMethodBinding method, Function<ITypeBinding, String> parameterTypeName) {
        IMethodBinding declaration = method.getMethodDeclaration();
        String parameters = Arrays.stream(declaration.getParameterTypes())
                .map(parameterTypeName)
                .collect(Collectors.joining(","));
        return typeKey(declaration.getDeclaringClass()) + "#" + memberName(declaration) + "(" + parameters + ")";
    }

    static String fieldKey(IVariableBinding field) {
        IVariableBinding declaration = field.getVariableDeclaration();
        return typeKey(declaration.getDeclaringClass()) + "." + declaration.getName();
    }

    static String nameOnlyMethodKey(String classFqn, String methodName, int argumentCount) {
        return classFqn + "#" + methodName + "/" + argumentCount;
    }

    static String nameOnlyConstructorKey(String classFqn, int argumentCount) {
        return nameOnlyMethodKey(classFqn, "<init>", argumentCount);
    }

    static String memberName(IMethodBinding method) {
        return method.isConstructor() ? "<init>" : method.getName();
    }

    static String parameterTypeName(ITypeBinding type) {
        if (type.isArray()) {
            return parameterTypeName(type.getElementType()) + "[]".repeat(type.getDimensions());
        }
        if (type.isPrimitive()) {
            return type.getName();
        }
        return typeKey(type);
    }

    /**
     * JDT keeps the source spelling of a type that is missing from the classpath as its binary name
     * ({@code OrderDto}, {@code Outer.Inner}, {@code com.corp.dto.OrderDto}) without type arguments;
     * {@code getName()} keeps only the last segment.
     */
    static String spelledName(ITypeBinding type) {
        String spelled = type.getBinaryName();
        String name = spelled == null || spelled.isEmpty() ? type.getName() : spelled;
        int typeArguments = name.indexOf('<');
        return typeArguments < 0 ? name : name.substring(0, typeArguments);
    }
}
