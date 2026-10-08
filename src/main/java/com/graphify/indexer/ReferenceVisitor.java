package com.graphify.indexer;

import com.graphify.indexer.model.Confidence;
import com.graphify.indexer.model.UsageKind;
import java.util.Optional;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;

/** Usages other than declarations and hierarchy: calls, instantiations, method references, fields, types, annotations. */
final class ReferenceVisitor extends ScopedVisitor {

    ReferenceVisitor(FileContext ctx) {
        super(ctx);
    }

    @Override
    public boolean visit(MethodInvocation node) {
        IMethodBinding binding = node.resolveMethodBinding();
        if (isResolved(binding)) {
            call(UsageKind.CALL, binding, node);
        } else {
            nameOnlyCall(node.getExpression(), node.getName().getIdentifier(), node.arguments().size(), node);
        }
        return true;
    }

    @Override
    public boolean visit(SuperMethodInvocation node) {
        resolvedOrWarn(UsageKind.CALL, node.resolveMethodBinding(), node, "Unresolved super call " + node.getName());
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        if (node.getAnonymousClassDeclaration() != null) {
            anonymousSuperConstructor(node);
            return true;
        }
        IMethodBinding binding = node.resolveConstructorBinding();
        if (isResolved(binding)) {
            call(UsageKind.INSTANTIATION, binding, node);
            return true;
        }
        nameOnlyConstructor(node);
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        resolvedOrWarn(UsageKind.CALL, node.resolveConstructorBinding(), node, "Unresolved this(...) call");
        return true;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        resolvedOrWarn(UsageKind.CALL, node.resolveConstructorBinding(), node, "Unresolved super(...) call");
        return true;
    }

    @Override
    public boolean visit(ExpressionMethodReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    @Override
    public boolean visit(TypeMethodReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    @Override
    public boolean visit(SuperMethodReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    @Override
    public boolean visit(CreationReference node) {
        resolvedOrWarn(UsageKind.METHOD_REF, node.resolveMethodBinding(), node, "Unresolved method reference " + node);
        return true;
    }

    @Override
    public boolean visit(SimpleName node) {
        if (node.isDeclaration()) {
            return true;
        }
        if (node.resolveBinding() instanceof IVariableBinding variable && variable.isField()
                && !(variable.getDeclaringClass() != null && variable.getDeclaringClass().isRecovered())) {
            UsageKind kind = isWriteTarget(node) ? UsageKind.FIELD_WRITE : UsageKind.FIELD_READ;
            ctx.usage(currentScope(), ctx.symbols.field(variable), kind, ctx.confidence.of(variable, node), node);
        }
        return true;
    }

    /** {@code A(1)} in an enum body runs the enum's constructor; the implicit default constructor is not recorded. */
    @Override
    public boolean visit(EnumConstantDeclaration node) {
        IMethodBinding binding = node.resolveConstructorBinding();
        if (isResolved(binding) && !binding.isDefaultConstructor()) {
            call(UsageKind.INSTANTIATION, binding, node);
        }
        return true;
    }

    @Override
    public boolean visit(SimpleType node) {
        typeReference(node);
        return true;
    }

    @Override
    public boolean visit(QualifiedType node) {
        typeReference(node);
        return true;
    }

    @Override
    public boolean visit(MarkerAnnotation node) {
        annotation(node);
        return true;
    }

    @Override
    public boolean visit(NormalAnnotation node) {
        annotation(node);
        return true;
    }

    @Override
    public boolean visit(SingleMemberAnnotation node) {
        annotation(node);
        return true;
    }

    private static boolean isWriteTarget(SimpleName name) {
        ASTNode target = name;
        while (true) {
            ASTNode parent = target.getParent();
            boolean nameOfAccess = (parent instanceof FieldAccess access && access.getName() == target)
                    || (parent instanceof SuperFieldAccess superAccess && superAccess.getName() == target)
                    || (parent instanceof QualifiedName qualified && qualified.getName() == target);
            if (nameOfAccess || parent instanceof ParenthesizedExpression) {
                target = parent;
            } else {
                break;
            }
        }
        ASTNode parent = target.getParent();
        if (parent instanceof Assignment assignment) {
            return assignment.getLeftHandSide() == target;
        }
        if (parent instanceof PostfixExpression) {
            return true;
        }
        return parent instanceof PrefixExpression prefix
                && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                        || prefix.getOperator() == PrefixExpression.Operator.DECREMENT);
    }

    private void typeReference(Type type) {
        if (type.isVar() || isConstructedOrSupertype(type)
                || type.getLocationInParent() == QualifiedType.QUALIFIER_PROPERTY) {
            return;
        }
        ITypeBinding binding = type.resolveBinding();
        if (binding != null && binding.isTypeVariable()) {
            return;
        }
        if (binding != null && !binding.isRecovered()) {
            ctx.usage(currentScope(), ctx.symbols.type(binding), UsageKind.TYPE_REF,
                    ctx.confidence.of(binding, type), type);
            return;
        }
        ctx.nameOnly.typeClass(type).ifPresent(fqn -> ctx.usage(currentScope(), ctx.symbols.nameOnlyType(fqn),
                UsageKind.TYPE_REF, Confidence.NAME_ONLY, type));
    }

    /** {@code new Foo<>()} is an INSTANTIATION and {@code extends Foo} is hierarchy; neither is a TYPE_REF. */
    private static boolean isConstructedOrSupertype(Type type) {
        ASTNode node = type;
        while (node.getLocationInParent() == ParameterizedType.TYPE_PROPERTY) {
            node = node.getParent();
        }
        StructuralPropertyDescriptor location = node.getLocationInParent();
        return location == ClassInstanceCreation.TYPE_PROPERTY
                || location == TypeDeclaration.SUPERCLASS_TYPE_PROPERTY
                || location == TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY
                || location == EnumDeclaration.SUPER_INTERFACE_TYPES_PROPERTY
                || location == RecordDeclaration.SUPER_INTERFACE_TYPES_PROPERTY;
    }

    /** The snippet is the annotation's source text, normalized to one line, so later plans can read e.g. endpoint paths from it. */
    private void annotation(Annotation node) {
        IAnnotationBinding binding = node.resolveAnnotationBinding();
        ITypeBinding type = binding == null ? null : binding.getAnnotationType();
        String text = ctx.sourceText(node);
        String flat = node.toString();
        // Defensive: if offsets ever misalign with our decoded text, fall back to JDT's flattened form.
        String snippet = text.startsWith("@") && text.charAt(text.length() - 1) == flat.charAt(flat.length() - 1)
                ? text : flat;
        if (type != null && !type.isRecovered()) {
            ctx.usage(currentScope(), ctx.symbols.type(type), UsageKind.ANNOTATION, ctx.confidence.of(type, node),
                    node, snippet);
            return;
        }
        ctx.nameOnly.annotationClass(node.getTypeName()).ifPresent(fqn -> ctx.usage(currentScope(),
                ctx.symbols.nameOnlyType(fqn), UsageKind.ANNOTATION, Confidence.NAME_ONLY, node, snippet));
    }

    /** A binding whose declaring class is recovered carries JDT's guessed (wrong) package, so it is not used. */
    private static boolean isResolved(IMethodBinding binding) {
        return binding != null && !binding.getDeclaringClass().isRecovered();
    }

    private void call(UsageKind kind, IMethodBinding binding, ASTNode node) {
        ctx.usage(currentScope(), ctx.symbols.method(binding), kind, ctx.confidence.of(binding, node), node);
    }

    private void resolvedOrWarn(UsageKind kind, IMethodBinding binding, ASTNode node, String warning) {
        if (isResolved(binding)) {
            call(kind, binding, node);
        } else {
            ctx.warning(node, warning);
        }
    }

    /** A receiver-less call that JDT cannot bind is most likely inherited from a superclass missing from the classpath. */
    private Optional<String> enclosingSuperclass(ASTNode node) {
        ASTNode current = node.getParent();
        while (current != null && !(current instanceof TypeDeclaration)) {
            current = current.getParent();
        }
        if (current instanceof TypeDeclaration type && type.getSuperclassType() != null) {
            return ctx.nameOnly.typeClass(type.getSuperclassType());
        }
        return Optional.empty();
    }

    /**
     * {@code new Base(args) { ... }} runs a constructor of {@code Base}. JDT binds the creation to the anonymous
     * class's own constructor, whose parameters mirror the super constructor it delegates to. Interfaces have none.
     */
    private void anonymousSuperConstructor(ClassInstanceCreation node) {
        ITypeBinding anonymous = node.getAnonymousClassDeclaration().resolveBinding();
        if (anonymous != null && anonymous.getInterfaces().length > 0) {
            return;
        }
        ITypeBinding superclass = anonymous == null ? null : anonymous.getSuperclass();
        if (superclass == null || superclass.isRecovered()) {
            nameOnlyConstructor(node);
            return;
        }
        if (Object.class.getName().equals(superclass.getErasure().getQualifiedName())) {
            return;
        }
        IMethodBinding anonymousConstructor = node.resolveConstructorBinding();
        IMethodBinding superConstructor = anonymousConstructor == null ? null
                : matchingConstructor(superclass, anonymousConstructor.getParameterTypes());
        if (superConstructor == null) {
            ctx.warning(node, "Unresolved super constructor of anonymous " + node.getType());
            return;
        }
        call(UsageKind.INSTANTIATION, superConstructor, node);
    }

    private static IMethodBinding matchingConstructor(ITypeBinding type, ITypeBinding[] parameterTypes) {
        for (IMethodBinding candidate : type.getDeclaredMethods()) {
            if (candidate.isConstructor() && sameErasures(candidate.getParameterTypes(), parameterTypes)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean sameErasures(ITypeBinding[] left, ITypeBinding[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            if (!left[i].getErasure().isEqualTo(right[i].getErasure())) {
                return false;
            }
        }
        return true;
    }

    private void nameOnlyConstructor(ClassInstanceCreation node) {
        ctx.nameOnly.typeClass(node.getType()).ifPresentOrElse(
                fqn -> ctx.usage(currentScope(), ctx.symbols.nameOnlyConstructor(fqn, node.arguments().size()),
                        UsageKind.INSTANTIATION, Confidence.NAME_ONLY, node),
                () -> ctx.warning(node, "Unresolved constructor " + node.getType()));
    }

    private void nameOnlyCall(Expression receiver, String methodName, int argumentCount, ASTNode node) {
        Optional<String> owner = receiver != null ? ctx.nameOnly.receiverClass(receiver)
                : ctx.nameOnly.staticImportClass(methodName).or(() -> enclosingSuperclass(node));
        owner.ifPresentOrElse(
                fqn -> ctx.usage(currentScope(), ctx.symbols.nameOnlyMethod(fqn, methodName, argumentCount),
                        UsageKind.CALL, Confidence.NAME_ONLY, node),
                () -> ctx.warning(node, "Unresolved call " + methodName + "/" + argumentCount));
    }
}
