/**
 * Copyright 2012-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.weaving;

import static com.google.common.base.Preconditions.checkNotNull;
import io.informant.weaving.ParsedType.Builder;

import java.io.IOException;
import java.security.CodeSource;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.RemappingMethodAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class WeavingClassVisitor extends ClassVisitor implements Opcodes {

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassVisitor.class);

    private static final Type adviceFlowType = Type.getType(AdviceFlowOuterHolder.class);

    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> advisors;
    @Nullable
    private final ClassLoader loader;
    private final ParsedTypeCache parsedTypeCache;
    @Nullable
    private final CodeSource codeSource;
    private ImmutableList<AdviceMatcher> adviceMatchers = ImmutableList.of();
    private ImmutableList<MixinType> matchedMixinTypes = ImmutableList.of();
    @LazyNonNull
    private Type type;
    private ImmutableMap<Advice, Integer> adviceFlowOuterHolderNums = ImmutableMap.of();
    private boolean writtenAdviceFlowThreadLocals = false;

    private int innerMethodCounter;

    private boolean nothingAtAllToWeave = false;
    @LazyNonNull
    private Builder parsedType;

    public WeavingClassVisitor(ImmutableList<MixinType> mixinTypes, ImmutableList<Advice> advisors,
            @Nullable ClassLoader loader, ParsedTypeCache parsedTypeCache,
            @Nullable CodeSource codeSource, ClassVisitor cv) {
        super(ASM4, cv);
        this.mixinTypes = mixinTypes;
        this.advisors = advisors;
        this.loader = loader;
        this.parsedTypeCache = parsedTypeCache;
        this.codeSource = codeSource;
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String/*@Nullable*/[] interfaceNamesNullable) {

        String[] interfaceNames = interfaceNamesNullable == null ? new String[0]
                : interfaceNamesNullable;
        parsedType = ParsedType.builder(TypeNames.fromInternal(name),
                TypeNames.fromInternal(superName), TypeNames.fromInternal(interfaceNames));
        if ((access & ACC_INTERFACE) != 0) {
            // interfaces never get woven
            nothingAtAllToWeave = true;
            return;
        }
        type = Type.getObjectType(name);
        List<ParsedType> superTypes = getSuperTypes(superName, interfaceNames);
        adviceMatchers = getAdviceMatchers(type, superTypes);
        matchedMixinTypes = getMatchedMixinTypes(type, superTypes);
        if (adviceMatchers.isEmpty() && matchedMixinTypes.isEmpty()) {
            nothingAtAllToWeave = true;
            return;
        }
        logger.debug("visit(): adviceMatchers={}", adviceMatchers);
        adviceFlowOuterHolderNums = getAdviceFlowOuterHolderNums(adviceMatchers);
        super.visit(version, access, name, signature, superName,
                getInterfacesIncludingMixins(interfaceNames, matchedMixinTypes));
    }

    @Override
    @Nullable
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions) {

        checkNotNull(parsedType, "Call to visit() is required");
        ParsedMethod parsedMethod = ParsedMethod.from(name,
                ImmutableList.copyOf(Type.getArgumentTypes(desc)),
                Type.getReturnType(desc), access);
        parsedType.addMethod(parsedMethod);
        if (nothingAtAllToWeave) {
            // no need to pass method on to class writer
            return null;
        }
        // type can be null, but not if nothingAtAllToWeave is false
        checkNotNull(type, "Call to visit() is required");
        if ((access & ACC_ABSTRACT) != 0) {
            // abstract methods never get woven
            return cv.visitMethod(access, name, desc, signature, exceptions);
        }
        MethodVisitor mv = null;
        if (name.equals("<clinit>")) {
            writeThreadLocalFields();
            mv = cv.visitMethod(access, name, desc, signature, exceptions);
            mv = new InitThreadLocals(mv, access, name, desc);
            // there can only be at most one clinit
            writtenAdviceFlowThreadLocals = true;
        } else if (name.equals("<init>") && !matchedMixinTypes.isEmpty()) {
            mv = cv.visitMethod(access, name, desc, signature, exceptions);
            mv = new InitMixins(mv, access, name, desc, matchedMixinTypes, type);
        }
        if ((access & ACC_SYNTHETIC) != 0) {
            return visitMethod(mv, access, name, desc, signature, exceptions);
        }
        List<Advice> matchingAdvisors = getMatchingAdvisors(access, parsedMethod);
        if (matchingAdvisors.isEmpty()) {
            return visitMethod(mv, access, name, desc, signature, exceptions);
        } else if (mv == null) {
            String innerWrappedName = wrapWithSyntheticMetricMarkerMethods(access, name, desc,
                    signature, exceptions, matchingAdvisors);
            String methodName = name;
            int methodAccess = access;
            if (innerWrappedName != null) {
                methodName = innerWrappedName;
                methodAccess = Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL + (access & ACC_STATIC);
            }
            mv = cv.visitMethod(methodAccess, methodName, desc, signature, exceptions);
            return new WeavingMethodVisitor(mv, methodAccess, methodName, desc, type,
                    matchingAdvisors, adviceFlowOuterHolderNums);
        } else {
            logger.warn("cannot add metrics to <clinit> or <init> methods at this time");
            return new WeavingMethodVisitor(mv, access, name, desc, type, matchingAdvisors,
                    adviceFlowOuterHolderNums);
        }
    }

    @Override
    public void visitEnd() {
        checkNotNull(parsedType, "Call to visit() is required");
        parsedTypeCache.add(parsedType.build(), loader);
        if (nothingAtAllToWeave) {
            return;
        }
        // type can be null, but not if nothingAtAllToWeave is false
        checkNotNull(type, "Call to visit() is required");
        if (!writtenAdviceFlowThreadLocals) {
            writeThreadLocalFields();
            MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            writeThreadLocalInitialization(mv);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        for (int i = 0; i < matchedMixinTypes.size(); i++) {
            ClassReader cr;
            try {
                cr = new ClassReader(matchedMixinTypes.get(i).getImplementation().getName());
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                continue;
            }
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            // SuppressWarnings because generics are explicitly removed from asm binaries
            // see http://forge.ow2.org/tracker/?group_id=23&atid=100023&func=detail&aid=316377
            @SuppressWarnings("unchecked")
            Iterator<FieldNode> fieldNodes = cn.fields.iterator();
            while (fieldNodes.hasNext()) {
                fieldNodes.next().accept(this);
            }
            // SuppressWarnings because generics are explicitly removed from asm binaries
            @SuppressWarnings("unchecked")
            Iterator<MethodNode> methodNodes = cn.methods.iterator();
            while (methodNodes.hasNext()) {
                MethodNode mn = methodNodes.next();
                if (mn.name.equals("<init>")) {
                    continue;
                }
                // SuppressWarnings because generics are explicitly removed from asm binaries
                @SuppressWarnings("unchecked")
                String[] exceptions = Iterables.toArray(mn.exceptions, String.class);
                MethodVisitor mv = cv.visitMethod(mn.access, mn.name, mn.desc, mn.signature,
                        exceptions);
                mn.accept(new RemappingMethodAdapter(mn.access, mn.desc, mv,
                        new SimpleRemapper(cn.name, type.getInternalName())));
            }
        }
    }

    boolean isNothingAtAllToWeave() {
        return nothingAtAllToWeave;
    }

    // it's ok if there are duplicates in the returned list (e.g. an interface that appears twice
    // in a type hierarchy), it's rare, dups don't cause an issue for callers, and so it doesn't
    // seem worth the (minor) performance hit to de-dup every time
    private List<ParsedType> getSuperTypes(@Nullable String superName, String[] interfaceNames) {
        List<ParsedType> superTypes = Lists.newArrayList();
        superTypes.addAll(parsedTypeCache.getTypeHierarchy(TypeNames.fromInternal(superName),
                loader));
        for (String interfaceName : interfaceNames) {
            superTypes.addAll(parsedTypeCache.getTypeHierarchy(
                    TypeNames.fromInternal(interfaceName), loader));
        }
        for (Iterator<ParsedType> i = superTypes.iterator(); i.hasNext();) {
            ParsedType superType = i.next();
            if (superType.isMissing()) {
                i.remove();
                logger.warn("type not found '{}' while recursing super types of '{}'{}",
                        new Object[] { superType.getName(),
                                type == null ? "???" : type.getClassName(),
                                codeSource == null ? "" : "(" + codeSource.getLocation() + ")" });
            }
        }
        return superTypes;
    }

    private ImmutableList<AdviceMatcher> getAdviceMatchers(Type type, List<ParsedType> superTypes) {
        ImmutableList.Builder<AdviceMatcher> adviceMatchersBuilder = ImmutableList.builder();
        for (Advice advice : advisors) {
            AdviceMatcher adviceMatcher = new AdviceMatcher(advice, type, superTypes);
            if (adviceMatcher.isClassLevelMatch()) {
                adviceMatchersBuilder.add(adviceMatcher);
            }
        }
        return adviceMatchersBuilder.build();
    }

    private ImmutableList<MixinType> getMatchedMixinTypes(Type type, List<ParsedType> superTypes) {
        ImmutableList.Builder<MixinType> matchedMixinTypesBuilder = ImmutableList.builder();
        for (MixinType mixinType : mixinTypes) {
            if (MixinMatcher.isMatch(mixinType, type, superTypes)) {
                matchedMixinTypesBuilder.add(mixinType);
            }
        }
        return matchedMixinTypesBuilder.build();
    }

    private ImmutableMap<Advice, Integer> getAdviceFlowOuterHolderNums(
            ImmutableList<AdviceMatcher> adviceMatchers) {
        ImmutableMap.Builder<Advice, Integer> adviceFlowOuterHolderNumsBuilder =
                ImmutableMap.builder();
        for (int i = 0; i < adviceMatchers.size(); i++) {
            adviceFlowOuterHolderNumsBuilder.put(adviceMatchers.get(i).getAdvice(), i);
        }
        return adviceFlowOuterHolderNumsBuilder.build();
    }

    private static String[] getInterfacesIncludingMixins(String[] interfaceNames,
            ImmutableList<MixinType> matchedMixinTypes) {
        if (matchedMixinTypes.isEmpty()) {
            return interfaceNames;
        }
        Set<String> interfacesIncludingMixins = Sets.newHashSet(interfaceNames);
        for (MixinType matchedMixinType : matchedMixinTypes) {
            for (Class<?> mixinInterface : matchedMixinType.getInterfaces()) {
                interfacesIncludingMixins.add(Type.getInternalName(mixinInterface));
            }
        }
        return Iterables.toArray(interfacesIncludingMixins, String.class);
    }

    private List<Advice> getMatchingAdvisors(int access, ParsedMethod parsedMethod) {
        List<Advice> matchingAdvisors = Lists.newArrayList();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(access, parsedMethod)) {
                matchingAdvisors.add(adviceMatcher.getAdvice());
            }
        }
        return matchingAdvisors;
    }

    private MethodVisitor visitMethod(@Nullable MethodVisitor mv, int access, String name,
            String desc, @Nullable String signature, String/*@Nullable*/[] exceptions) {
        if (mv == null) {
            return cv.visitMethod(access, name, desc, signature, exceptions);
        } else {
            return mv;
        }
    }

    // returns null if no synthetic metric marker methods were needed
    @Nullable
    private String wrapWithSyntheticMetricMarkerMethods(int outerAccess, String outerName,
            String desc, @Nullable String signature, String/*@Nullable*/[] exceptions,
            List<Advice> matchingAdvisors) {

        checkNotNull(type, "Call to visit() is required");
        int innerAccess = Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL + (outerAccess & ACC_STATIC);
        boolean first = true;
        String currMethodName = outerName;
        for (Advice advice : matchingAdvisors) {
            String metricName = advice.getPointcut().metricName();
            if (metricName.length() == 0) {
                continue;
            }
            String nextMethodName = outerName + "$informant$metric$" + metricName.replace(' ', '$')
                    + '$' + innerMethodCounter++;
            int access = first ? outerAccess : innerAccess;
            MethodVisitor mv2 = cv.visitMethod(access, currMethodName, desc, signature, exceptions);
            GeneratorAdapter mg = new GeneratorAdapter(mv2, access, nextMethodName, desc);
            if ((outerAccess & ACC_STATIC) == 0) {
                mg.loadThis();
                mg.loadArgs();
                mg.invokeVirtual(type, new Method(nextMethodName, desc));
            } else {
                mg.loadArgs();
                mg.invokeStatic(type, new Method(nextMethodName, desc));
            }
            mg.returnValue();
            mg.endMethod();
            currMethodName = nextMethodName;
            first = false;
        }
        return first ? null : currMethodName;
    }

    private void writeThreadLocalFields() {
        for (int i = 0; i < adviceMatchers.size(); i++) {
            if (!adviceMatchers.get(i).getAdvice().getPointcut().captureNested()) {
                super.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "informant$adviceFlow$" + i,
                        adviceFlowType.getDescriptor(), null, null);
            }
        }
    }

    private void writeThreadLocalInitialization(MethodVisitor mv) {
        checkNotNull(type, "Call to visit() is required");
        for (int i = 0; i < adviceMatchers.size(); i++) {
            if (!adviceMatchers.get(i).getAdvice().getPointcut().captureNested()) {
                // cannot use visitLdcInsn(Type) since .class constants are not supported in classes
                // that were compiled to jdk 1.4
                mv.visitLdcInsn(adviceMatchers.get(i).getAdvice().getAdviceType().getClassName());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                        "(Ljava/lang/String;)Ljava/lang/Class;");
                String adviceFlowInternalName = adviceFlowType.getInternalName();
                mv.visitMethodInsn(INVOKESTATIC, adviceFlowInternalName, "getSharedInstance",
                        "(Ljava/lang/Class;)L" + adviceFlowInternalName + ";");
                mv.visitFieldInsn(PUTSTATIC, type.getInternalName(), "informant$adviceFlow$" + i,
                        adviceFlowType.getDescriptor());
            }
        }
    }

    @Override
    public String toString() {
        // not including fields that are just direct copies from Weaver
        ToStringHelper toStringHelper = Objects.toStringHelper(this)
                .add("adviceFlowType", adviceFlowType)
                .add("codeSource", codeSource)
                .add("adviceMatchers", adviceMatchers)
                .add("matchedMixinTypes", matchedMixinTypes)
                .add("type", type)
                .add("adviceFlowThreadLocalNums", adviceFlowOuterHolderNums)
                .add("writtenAdviceFlowThreadLocals", writtenAdviceFlowThreadLocals)
                .add("innerMethodCounter", innerMethodCounter)
                .add("nothingAtAllToWeave", nothingAtAllToWeave);
        if (parsedType != null) {
            toStringHelper.add("parsedType", parsedType.build());
        }
        return toStringHelper.toString();
    }

    private static class InitMixins extends AdviceAdapter {

        @ReadOnly
        private final List<MixinType> matchedMixinTypes;
        private final Type type;
        private boolean cascadingConstructor;

        protected InitMixins(MethodVisitor mv, int access, String name, String desc,
                @ReadOnly List<MixinType> matchedMixinTypes, Type type) {
            super(ASM4, mv, access, name, desc);
            this.matchedMixinTypes = matchedMixinTypes;
            this.type = type;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc) {
            if (name.equals("<init>") && owner.equals(type.getInternalName())) {
                cascadingConstructor = true;
            }
            super.visitMethodInsn(opcode, owner, name, desc);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (cascadingConstructor) {
                // need to call MixinInit exactly once, so don't call MixinInit at end of cascading
                // constructors
                return;
            }
            for (int i = 0; i < matchedMixinTypes.size(); i++) {
                String initMethodName = matchedMixinTypes.get(i).getInitMethodName();
                if (initMethodName != null) {
                    loadThis();
                    invokeVirtual(type, new Method(initMethodName, "()V"));
                }
            }
        }
    }

    private class InitThreadLocals extends AdviceAdapter {

        protected InitThreadLocals(MethodVisitor mv, int access, String name, String desc) {
            super(ASM4, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            writeThreadLocalInitialization(this);
        }
    }
}
