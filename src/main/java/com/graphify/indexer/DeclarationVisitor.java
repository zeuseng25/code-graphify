package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/** Declarations of types, methods and fields, plus EXTENDS / IMPLEMENTS / OVERRIDES usages. */
final class DeclarationVisitor extends ScopedVisitor {

    DeclarationVisitor(FileContext ctx) {
        super(ctx);
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        super.visit(node);
        declareType(node);
        if (node.getSuperclassType() != null) {
            supertype(node.getSuperclassType(), UsageKind.EXTENDS);
        }
        UsageKind interfaceKind = node.isInterface() ? UsageKind.EXTENDS : UsageKind.IMPLEMENTS;
        for (Object type : node.superInterfaceTypes()) {
            supertype((Type) type, interfaceKind);
        }
        return true;
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        super.visit(node);
        declareType(node);
        for (Object type : node.superInterfaceTypes()) {
            supertype((Type) type, UsageKind.IMPLEMENTS);
        }
        return true;
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        super.visit(node);
        declareType(node);
        for (Object type : node.superInterfaceTypes()) {
            supertype((Type) type, UsageKind.IMPLEMENTS);
        }
        return true;
    }

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        super.visit(node);
        declareType(node);
        return true;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        super.visit(node);
        IMethodBinding binding = node.resolveBinding();
        if (binding == null) {
            return true;
        }
        String from = currentScope();
        if (!binding.getDeclaringClass().isAnonymous()) {
            ctx.declaration(from, node.getName());
        }
        for (IMethodBinding overridden : Overrides.of(binding)) {
            ctx.usage(from, ctx.symbols.method(overridden), UsageKind.OVERRIDES,
                    ctx.confidence.of(binding, node.getName()), node.getName());
        }
        return true;
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        for (Object item : node.fragments()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) item;
            IVariableBinding binding = fragment.resolveBinding();
            if (binding != null && binding.isField() && !binding.getDeclaringClass().isAnonymous()) {
                ctx.declaration(ctx.symbols.field(binding), fragment.getName());
            }
        }
        return true;
    }

    @Override
    public boolean visit(EnumConstantDeclaration node) {
        IVariableBinding binding = node.resolveVariable();
        if (binding != null) {
            ctx.declaration(ctx.symbols.field(binding), node.getName());
        }
        return true;
    }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        ITypeBinding anonymous = node.resolveBinding();
        if (anonymous == null || !(node.getParent() instanceof ClassInstanceCreation creation)) {
            return true;
        }
        boolean implementsInterface = anonymous.getInterfaces().length > 0;
        ITypeBinding base = implementsInterface ? anonymous.getInterfaces()[0] : anonymous.getSuperclass();
        UsageKind kind = implementsInterface ? UsageKind.IMPLEMENTS : UsageKind.EXTENDS;
        if (base != null && !base.isRecovered()) {
            ctx.usage(currentScope(), ctx.symbols.type(base), kind, ctx.confidence.of(base, creation.getType()),
                    creation.getType());
        } else {
            ctx.nameOnly.typeClass(creation.getType()).ifPresent(fqn -> ctx.usage(currentScope(),
                    ctx.symbols.nameOnlyType(fqn), kind, Confidence.NAME_ONLY, creation.getType()));
        }
        return true;
    }

    private void declareType(AbstractTypeDeclaration node) {
        if (node.resolveBinding() != null) {
            ctx.declaration(currentScope(), node.getName());
        }
    }

    private void supertype(Type type, UsageKind kind) {
        String from = currentScope();
        ITypeBinding binding = type.resolveBinding();
        if (binding != null && !binding.isRecovered()) {
            ctx.usage(from, ctx.symbols.type(binding), kind, ctx.confidence.of(binding, type), type);
            return;
        }
        ctx.nameOnly.typeClass(type).ifPresentOrElse(
                fqn -> ctx.usage(from, ctx.symbols.nameOnlyType(fqn), kind, Confidence.NAME_ONLY, type),
                () -> ctx.warning(type, "Unresolved supertype " + type));
    }
}
