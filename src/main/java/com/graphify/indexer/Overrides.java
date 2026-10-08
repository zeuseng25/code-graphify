package com.graphify.indexer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

/** Every method that a method overrides or implements, searched through all supertypes. */
final class Overrides {

    private Overrides() {
    }

    static List<IMethodBinding> of(IMethodBinding method) {
        int modifiers = method.getModifiers();
        if (method.isConstructor() || Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)) {
            return List.of();
        }
        Map<String, IMethodBinding> found = new LinkedHashMap<>();
        Set<String> visitedTypes = new HashSet<>();
        Deque<ITypeBinding> queue = new ArrayDeque<>(supertypes(method.getDeclaringClass()));
        while (!queue.isEmpty()) {
            ITypeBinding type = queue.poll();
            if (!visitedTypes.add(SymbolKeys.typeKey(type))) {
                continue;
            }
            for (IMethodBinding candidate : type.getDeclaredMethods()) {
                if (method.overrides(candidate)) {
                    found.putIfAbsent(SymbolKeys.methodKey(candidate), candidate);
                }
            }
            queue.addAll(supertypes(type));
        }
        return List.copyOf(found.values());
    }

    private static List<ITypeBinding> supertypes(ITypeBinding type) {
        List<ITypeBinding> supertypes = new ArrayList<>();
        if (type.getSuperclass() != null) {
            supertypes.add(type.getSuperclass());
        }
        supertypes.addAll(Arrays.asList(type.getInterfaces()));
        return supertypes;
    }
}
