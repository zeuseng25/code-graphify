package com.graphify.indexer;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * Tracks the symbol that owns the code being visited: the nearest named method/constructor, else the class.
 * Lambdas and anonymous classes do not open a scope, so their code belongs to the enclosing named method.
 */
abstract class ScopedVisitor extends ASTVisitor {

    protected final FileContext ctx;
    private final List<String> scopes = new ArrayList<>();

    ScopedVisitor(FileContext ctx) {
        super(false);
        this.ctx = ctx;
    }

    protected final String currentScope() {
        return scopes.isEmpty() ? null : scopes.getLast();
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(TypeDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(EnumDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(RecordDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(RecordDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(AnnotationTypeDeclaration node) {
        return enterType(node.resolveBinding());
    }

    @Override
    public void endVisit(AnnotationTypeDeclaration node) {
        exit();
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        IMethodBinding binding = node.resolveBinding();
        boolean ownScope = binding != null && !binding.getDeclaringClass().isAnonymous();
        scopes.add(ownScope ? ctx.symbols.method(binding) : currentScope());
        return true;
    }

    @Override
    public void endVisit(MethodDeclaration node) {
        exit();
    }

    private boolean enterType(ITypeBinding binding) {
        scopes.add(binding == null ? currentScope() : ctx.symbols.type(binding));
        return true;
    }

    private void exit() {
        scopes.removeLast();
    }
}
