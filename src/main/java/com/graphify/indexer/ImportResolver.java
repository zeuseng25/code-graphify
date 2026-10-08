package com.graphify.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;

/**
 * Turns a simple (or dotted nested) type name into a fully qualified binary name using the file's imports.
 * Used only when JDT has no binding; JDT's own guess for a missing type puts it in the current package, which
 * is wrong whenever the type is imported. Works on units parsed with or without bindings.
 */
final class ImportResolver {

    private final String packageName;
    private final Map<String, String> singleTypeImports = new HashMap<>();
    private final List<String> onDemandPackages = new ArrayList<>();
    private final Map<String, String> staticMemberImports = new HashMap<>();
    private final List<String> staticOnDemandClasses = new ArrayList<>();

    ImportResolver(CompilationUnit unit) {
        this.packageName = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
        for (Object item : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) item;
            String name = declaration.getName().getFullyQualifiedName();
            if (declaration.isStatic()) {
                if (declaration.isOnDemand()) {
                    staticOnDemandClasses.add(name);
                } else {
                    staticMemberImports.put(name.substring(name.lastIndexOf('.') + 1),
                            name.substring(0, Math.max(0, name.lastIndexOf('.'))));
                }
            } else if (declaration.isOnDemand()) {
                onDemandPackages.add(name);
            } else {
                singleTypeImports.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
    }

    Optional<String> resolve(String typeName) {
        if (typeName.isEmpty()) {
            return Optional.empty();
        }
        int dot = typeName.indexOf('.');
        if (Character.isLowerCase(typeName.charAt(0))) {
            return looksQualified(typeName) ? Optional.of(binaryName(typeName)) : Optional.empty();
        }
        String first = dot > 0 ? typeName.substring(0, dot) : typeName;
        String nested = typeName.substring(first.length()).replace('.', '$');
        String imported = singleTypeImports.get(first);
        if (imported != null) {
            return Optional.of(imported + nested);
        }
        if (onDemandPackages.size() == 1) {
            return Optional.of(onDemandPackages.getFirst() + "." + first + nested);
        }
        return Optional.empty();
    }

    /** {@code com.x.Outer.Inner} → {@code com.x.Outer$Inner}: segments after the first type name are nested types. */
    private static String binaryName(String qualifiedName) {
        String[] segments = qualifiedName.split("\\.");
        StringBuilder binary = new StringBuilder(segments[0]);
        boolean insideType = false;
        for (int i = 1; i < segments.length; i++) {
            binary.append(insideType ? '$' : '.').append(segments[i]);
            insideType |= !segments[i].isEmpty() && Character.isUpperCase(segments[i].charAt(0));
        }
        return binary.toString();
    }

    /** {@code org.vendor.Client} names a type; {@code log} or {@code foo.bar} is a variable or package, not a type. */
    private static boolean looksQualified(String name) {
        String[] segments = name.split("\\.");
        for (int i = 1; i < segments.length; i++) {
            if (!segments[i].isEmpty() && Character.isUpperCase(segments[i].charAt(0))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Like {@link #resolve} for a type named in this file's own declarations: when no import matches and the
     * file has no on-demand imports, Java puts the type in the file's own package.
     */
    Optional<String> resolveDeclared(String typeName) {
        Optional<String> imported = resolve(typeName);
        if (imported.isPresent() || !onDemandPackages.isEmpty()) {
            return imported;
        }
        String binaryName = typeName.replace('.', '$');
        return Optional.of(packageName.isEmpty() ? binaryName : packageName + "." + binaryName);
    }

    /** The class a statically imported member comes from: its single static import, else the only static wildcard. */
    Optional<String> staticImportClass(String memberName) {
        String imported = staticMemberImports.get(memberName);
        if (imported != null) {
            return Optional.of(imported);
        }
        return staticOnDemandClasses.size() == 1 ? Optional.of(staticOnDemandClasses.getFirst()) : Optional.empty();
    }
}
