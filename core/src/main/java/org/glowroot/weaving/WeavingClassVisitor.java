/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.weaving;

import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import com.google.common.base.CharMatcher;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
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

import org.glowroot.common.Reflections.ReflectiveException;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ASM5;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.F_SAME1;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

class WeavingClassVisitor extends ClassVisitor {

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassVisitor.class);

    private static final CharMatcher metricNameCharMatcher =
            CharMatcher.JAVA_LETTER_OR_DIGIT.or(CharMatcher.is(' '));

    private static final Set<String> invalidMetricNameSet = Sets.newConcurrentHashSet();

    private static final AtomicLong metaHolderCounter = new AtomicLong();

    private final ClassVisitor cv;

    private final @Nullable ClassLoader loader;

    private final AnalyzingClassVisitor analyzingClassVisitor;
    private final AnalyzedWorld analyzedWorld;

    private final boolean metricWrapperMethods;

    private @MonotonicNonNull Type type;

    private boolean throwShortCircuitException;
    private boolean interfaceSoNothingToWeave;

    private int innerMethodCounter;

    // these are for handling class and method metas
    private boolean maybeHasMetas;
    private final Set<Type> classMetaTypes = Sets.newHashSet();
    private final Set<MethodMetaGroup> methodMetaGroups = Sets.newHashSet();
    private @MonotonicNonNull String metaHolderInternalName;
    private int methodMetaCounter;

    public WeavingClassVisitor(ClassVisitor cv, List<Advice> advisors,
            ImmutableList<MixinType> mixinTypes, @Nullable ClassLoader loader,
            AnalyzedWorld analyzedWorld, @Nullable CodeSource codeSource,
            boolean metricWrapperMethods) {
        super(ASM5, cv);
        this.cv = cv;
        this.loader = loader;
        analyzingClassVisitor =
                new AnalyzingClassVisitor(advisors, mixinTypes, loader, analyzedWorld, codeSource);
        this.analyzedWorld = analyzedWorld;
        this.metricWrapperMethods = metricWrapperMethods;
    }

    @Override
    public void visit(int version, int access, String internalName, @Nullable String signature,
            @Nullable String superInternalName,
            String/*@Nullable*/[] interfaceInternalNamesNullable) {

        AnalyzedClass nonInterestingAnalyzedClass =
                analyzingClassVisitor.visitAndSometimesReturnNonInterestingAnalyzedClass(access,
                        internalName, superInternalName, interfaceInternalNamesNullable);
        if (nonInterestingAnalyzedClass != null) {
            // performance optimization
            analyzedWorld.add(nonInterestingAnalyzedClass, loader);
            throwShortCircuitException = true;
            return;
        }
        interfaceSoNothingToWeave = Modifier.isInterface(access);
        if (interfaceSoNothingToWeave) {
            return;
        }
        for (AdviceMatcher adviceMatcher : analyzingClassVisitor.getAdviceMatchers()) {
            if (!adviceMatcher.advice().classMetaTypes().isEmpty()
                    || !adviceMatcher.advice().methodMetaTypes().isEmpty()) {
                maybeHasMetas = true;
                break;
            }
        }
        if (!maybeHasMetas) {
            List<AnalyzedClass> superAnalyzedClasses =
                    analyzingClassVisitor.getSuperAnalyzedClasses();
            outer: for (AnalyzedClass analyzedClass : superAnalyzedClasses) {
                for (AnalyzedMethod analyzedMethod : analyzedClass.analyzedMethods()) {
                    for (Advice advice : analyzedMethod.advisors()) {
                        if (!advice.classMetaTypes().isEmpty()
                                || !advice.methodMetaTypes().isEmpty()) {
                            maybeHasMetas = true;
                            break outer;
                        }
                    }
                }
            }
        }
        type = Type.getObjectType(internalName);
        String/*@Nullable*/[] interfacesIncludingMixins = getInterfacesIncludingMixins(
                interfaceInternalNamesNullable, analyzingClassVisitor.getMatchedMixinTypes());
        cv.visit(version, access, internalName, signature, superInternalName,
                interfacesIncludingMixins);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.equals("Lorg/glowroot/api/weaving/Pointcut;")) {
            throw PointcutClassFoundException.INSTANCE;
        }
        return cv.visitAnnotation(desc, visible);
    }

    @Override
    public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions) {
        if (throwShortCircuitException) {
            // this is in visitMethod because need to check annotations first
            throw ShortCircuitException.INSTANCE;
        }
        List<Advice> matchingAdvisors = analyzingClassVisitor.visitMethodAndReturnAdvisors(access,
                name, desc, signature, exceptions);
        if (interfaceSoNothingToWeave) {
            return null;
        }
        checkNotNull(type); // type is non null if there is something to weave
        if (Modifier.isAbstract(access) || Modifier.isNative(access)
                || (access & ACC_SYNTHETIC) != 0) {
            // don't try to weave abstract, native and synthetic methods
            return cv.visitMethod(access, name, desc, signature, exceptions);
        }
        if (name.equals("<init>") && !analyzingClassVisitor.getMatchedMixinTypes().isEmpty()) {
            return visitInitWithMixin(access, name, desc, signature, exceptions, matchingAdvisors);
        }
        if (matchingAdvisors.isEmpty()) {
            return cv.visitMethod(access, name, desc, signature, exceptions);
        }
        return visitMethodWithAdvice(access, name, desc, signature, exceptions, matchingAdvisors);
    }

    @Override
    public void visitEnd() {
        if (throwShortCircuitException) {
            // this is in visitEnd also in case there were no methods
            throw ShortCircuitException.INSTANCE;
        }
        analyzingClassVisitor.visitEnd();
        AnalyzedClass analyzedClass = analyzingClassVisitor.getAnalyzedClass();
        checkNotNull(analyzedClass); // analyzedClass is non-null after visiting the class
        analyzedWorld.add(analyzedClass, loader);
        if (interfaceSoNothingToWeave) {
            return;
        }
        checkNotNull(type); // type is non null if there is something to weave
        for (MixinType mixinType : analyzingClassVisitor.getMatchedMixinTypes()) {
            addMixin(mixinType);
        }
        handleInheritedMethodsThatNowFulfillAdvice(analyzedClass);
        // handle metas at end, since handleInheritedMethodsThatNowFulfillAdvice()
        // above could add new metas
        if (metaHolderInternalName != null) {
            if (loader == null) {
                initializeBoostrapMetaHolders();
            } else {
                try {
                    generateMetaHolder();
                } catch (ReflectiveException e) {
                    logger.error(e.getMessage(), e);
                    throw ShortCircuitException.INSTANCE;
                }
            }
        }
        cv.visitEnd();
    }

    boolean isInterfaceSoNothingToWeave() {
        return interfaceSoNothingToWeave;
    }

    @RequiresNonNull({"type", "metaHolderInternalName"})
    private void initializeBoostrapMetaHolders() {
        for (Type classMetaType : classMetaTypes) {
            String classMetaInternalName = classMetaType.getInternalName();
            String classMetaFieldName =
                    "glowroot$class$meta$" + classMetaInternalName.replace('/', '$');
            BootstrapMetaHolders.createClassMetaHolder(metaHolderInternalName, classMetaFieldName,
                    classMetaType, type);
        }
        for (MethodMetaGroup methodMetaGroup : methodMetaGroups) {
            for (Type methodMetaType : methodMetaGroup.getMethodMetaTypes()) {
                String methodMetaInternalName = methodMetaType.getInternalName();
                String methodMetaFieldName = "glowroot$method$meta$"
                        + methodMetaGroup.getUniqueNum() + '$'
                        + methodMetaInternalName.replace('/', '$');
                BootstrapMetaHolders.createMethodMetaHolder(metaHolderInternalName,
                        methodMetaFieldName, methodMetaType, type, methodMetaGroup.getReturnType(),
                        methodMetaGroup.getParameterTypes());
            }
        }
    }

    @RequiresNonNull({"type", "metaHolderInternalName", "loader"})
    private void generateMetaHolder() throws ReflectiveException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, metaHolderInternalName, null, "java/lang/Object",
                null);
        Type metaHolderType = Type.getObjectType(metaHolderInternalName);
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        Label l0 = new Label();
        Label l1 = new Label();
        Label l2 = new Label();
        mv.visitTryCatchBlock(l0, l1, l2, "java/lang/ClassNotFoundException");
        mv.visitLabel(l0);
        for (Type classMetaType : classMetaTypes) {
            String classMetaInternalName = classMetaType.getInternalName();
            String classMetaFieldName = "glowroot$class$meta$"
                    + classMetaInternalName.replace('/', '$');
            FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC,
                    classMetaFieldName, "L" + classMetaInternalName + ";", null, null);
            fv.visitEnd();
            mv.visitTypeInsn(NEW, classMetaInternalName);
            mv.visitInsn(DUP);
            loadType(mv, type, metaHolderType);
            mv.visitMethodInsn(INVOKESPECIAL, classMetaInternalName,
                    "<init>", "(Ljava/lang/Class;)V", false);
            mv.visitFieldInsn(PUTSTATIC, metaHolderInternalName, classMetaFieldName,
                    "L" + classMetaInternalName + ";");
        }
        for (MethodMetaGroup methodMetaGroup : methodMetaGroups) {
            for (Type methodMetaType : methodMetaGroup.getMethodMetaTypes()) {
                String methodMetaInternalName = methodMetaType.getInternalName();
                String methodMetaFieldName = "glowroot$method$meta$"
                        + methodMetaGroup.getUniqueNum() + '$'
                        + methodMetaInternalName.replace('/', '$');
                FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC,
                        methodMetaFieldName, "L" + methodMetaInternalName + ";", null, null);
                fv.visitEnd();
                mv.visitTypeInsn(NEW, methodMetaInternalName);
                mv.visitInsn(DUP);
                loadType(mv, type, metaHolderType);
                loadType(mv, methodMetaGroup.getReturnType(), metaHolderType);

                mv.visitIntInsn(BIPUSH, methodMetaGroup.getParameterTypes().size());
                mv.visitTypeInsn(ANEWARRAY, "java/lang/Class");
                for (int i = 0; i < methodMetaGroup.getParameterTypes().size(); i++) {
                    mv.visitInsn(DUP);
                    mv.visitIntInsn(BIPUSH, i);
                    loadType(mv, methodMetaGroup.getParameterTypes().get(i), metaHolderType);
                    mv.visitInsn(AASTORE);
                }

                mv.visitMethodInsn(INVOKESPECIAL, methodMetaInternalName, "<init>",
                        "(Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)V", false);
                mv.visitFieldInsn(PUTSTATIC, metaHolderInternalName, methodMetaFieldName,
                        "L" + methodMetaInternalName + ";");
            }
        }
        // this is just try/catch ClassNotFoundException/re-throw AssertionError
        mv.visitLabel(l1);
        Label l3 = new Label();
        mv.visitJumpInsn(GOTO, l3);
        mv.visitLabel(l2);
        mv.visitFrame(F_SAME1, 0, null, 1, new Object[] {"java/lang/ClassNotFoundException"});
        mv.visitVarInsn(ASTORE, 0);
        mv.visitTypeInsn(NEW, "java/lang/AssertionError");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>",
                "(Ljava/lang/Object;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(l3);
        mv.visitFrame(F_SAME, 0, null, 0, null);

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        ClassLoaders.defineClass(ClassNames.fromInternalName(metaHolderInternalName), bytes,
                loader);
    }

    private static void loadType(MethodVisitor mv, Type type, Type ownerType) {
        switch (type.getSort()) {
            case Type.VOID:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Void", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.BOOLEAN:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.CHAR:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Character", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.BYTE:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Byte", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.SHORT:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Short", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.INT:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.FLOAT:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Float", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.LONG:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.DOUBLE:
                mv.visitFieldInsn(GETSTATIC, "java/lang/Double", "TYPE", "Ljava/lang/Class;");
                break;
            case Type.ARRAY:
                loadType(mv, type.getElementType(), ownerType);
                mv.visitIntInsn(BIPUSH, type.getDimensions());
                mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(GeneratedBytecodeUtil.class),
                        "getArrayClass", "(Ljava/lang/Class;I)Ljava/lang/Class;", false);
                break;
            default:
                // may not have access to type in meta holder, so need to use Class.forName()
                // instead of class constant
                mv.visitLdcInsn(type.getClassName());
                mv.visitInsn(ICONST_0);
                mv.visitLdcInsn(ownerType);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                        "()Ljava/lang/ClassLoader;", false);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                        "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        }
    }

    private static String/*@Nullable*/[] getInterfacesIncludingMixins(
            String/*@Nullable*/[] interfaces, ImmutableList<MixinType> matchedMixinTypes) {
        if (matchedMixinTypes.isEmpty()) {
            return interfaces;
        }
        Set<String> interfacesIncludingMixins = Sets.newHashSet();
        if (interfaces != null) {
            interfacesIncludingMixins.addAll(Arrays.asList(interfaces));
        }
        for (MixinType matchedMixinType : matchedMixinTypes) {
            for (Type mixinInterface : matchedMixinType.interfaces()) {
                interfacesIncludingMixins.add(mixinInterface.getInternalName());
            }
        }
        return Iterables.toArray(interfacesIncludingMixins, String.class);
    }

    @RequiresNonNull("type")
    private MethodVisitor visitInitWithMixin(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions,
            List<Advice> matchingAdvisors) {
        Integer methodMetaUniqueNum = collectMetasAtMethod(matchingAdvisors, desc);
        MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
        checkNotNull(mv);
        mv = new InitMixins(mv, access, name, desc, analyzingClassVisitor.getMatchedMixinTypes(),
                type);
        for (Advice advice : matchingAdvisors) {
            if (advice.pointcut().metricName().length() != 0) {
                logger.warn("cannot add metric to <clinit> or <init> methods at this time");
                break;
            }
        }
        return new WeavingMethodVisitor(mv, access, name, desc, type, matchingAdvisors,
                metaHolderInternalName, methodMetaUniqueNum, loader == null, null);
    }

    @RequiresNonNull("type")
    private MethodVisitor visitMethodWithAdvice(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions,
            Iterable<Advice> matchingAdvisors) {
        Integer methodMetaUniqueNum = collectMetasAtMethod(matchingAdvisors, desc);
        if (metricWrapperMethods && !name.equals("<init>")) {
            return wrapWithSyntheticMetricMarkerMethods(access, name, desc, signature, exceptions,
                    matchingAdvisors, methodMetaUniqueNum);
        } else {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            checkNotNull(mv);
            return new WeavingMethodVisitor(mv, access, name, desc, type, matchingAdvisors,
                    metaHolderInternalName, methodMetaUniqueNum, loader == null, null);
        }
    }

    private @Nullable Integer collectMetasAtMethod(Iterable<Advice> matchingAdvisors, String desc) {
        Set<Type> methodMetaTypes = Sets.newHashSet();
        for (Advice matchingAdvice : matchingAdvisors) {
            classMetaTypes.addAll(matchingAdvice.classMetaTypes());
            methodMetaTypes.addAll(matchingAdvice.methodMetaTypes());
        }
        Integer methodMetaUniqueNum = null;
        if (!methodMetaTypes.isEmpty()) {
            methodMetaUniqueNum = ++methodMetaCounter;
            List<Type> parameterTypes = Arrays.asList(Type.getArgumentTypes(desc));
            Type returnType = Type.getReturnType(desc);
            methodMetaGroups.add(new MethodMetaGroup(returnType, parameterTypes,
                    methodMetaUniqueNum, methodMetaTypes));
        }
        if ((!classMetaTypes.isEmpty() || !methodMetaTypes.isEmpty())
                && metaHolderInternalName == null) {
            metaHolderInternalName =
                    "org/glowroot/weaving/MetaHolder" + metaHolderCounter.incrementAndGet();
        }
        return methodMetaUniqueNum;
    }

    // returns null if no synthetic metric marker methods were needed
    @RequiresNonNull("type")
    private WeavingMethodVisitor wrapWithSyntheticMetricMarkerMethods(int outerAccess,
            String outerName, String desc, @Nullable String signature,
            String/*@Nullable*/[] exceptions, Iterable<Advice> matchingAdvisors,
            @Nullable Integer methodMetaUniqueNum) {
        int innerAccess = ACC_PRIVATE + ACC_FINAL + (outerAccess & ACC_STATIC);
        MethodVisitor outerMethodVisitor = null;
        String currMethodName = outerName;
        int currMethodAccess = outerAccess;
        for (Advice advice : matchingAdvisors) {
            String metricName = advice.pointcut().metricName();
            if (metricName.isEmpty()) {
                continue;
            }
            if (!metricNameCharMatcher.matchesAllOf(metricName)) {
                logInvalidMetricNameWarningOnce(metricName);
                metricName = metricNameCharMatcher.negate().replaceFrom(metricName, '_');
            }
            String nextMethodName = outerName + "$glowroot$metric$" + metricName.replace(' ', '$')
                    + '$' + innerMethodCounter++;
            int access = outerMethodVisitor == null ? outerAccess : innerAccess;
            MethodVisitor mv = cv.visitMethod(access, currMethodName, desc, signature, exceptions);
            checkNotNull(mv);
            GeneratorAdapter mg = new GeneratorAdapter(mv, access, nextMethodName, desc);
            if (!Modifier.isStatic(outerAccess)) {
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
            currMethodAccess = innerAccess;
            if (outerMethodVisitor == null) {
                outerMethodVisitor = mg;
            }
        }
        MethodVisitor mv =
                cv.visitMethod(currMethodAccess, currMethodName, desc, signature, exceptions);
        checkNotNull(mv);
        return new WeavingMethodVisitor(mv, currMethodAccess, currMethodName, desc, type,
                matchingAdvisors, metaHolderInternalName, methodMetaUniqueNum, loader == null,
                outerMethodVisitor);
    }

    @RequiresNonNull("type")
    private void addMixin(MixinType mixinType) {
        ClassReader cr = new ClassReader(mixinType.implementationBytes());
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.EXPAND_FRAMES);
        // SuppressWarnings because generics are explicitly removed from asm binaries
        // see http://forge.ow2.org/tracker/?group_id=23&atid=100023&func=detail&aid=316377
        @SuppressWarnings("unchecked")
        List<FieldNode> fieldNodes = cn.fields;
        for (FieldNode fieldNode : fieldNodes) {
            fieldNode.accept(this);
        }
        // SuppressWarnings because generics are explicitly removed from asm binaries
        @SuppressWarnings("unchecked")
        List<MethodNode> methodNodes = cn.methods;
        for (MethodNode mn : methodNodes) {
            if (mn.name.equals("<init>")) {
                continue;
            }
            // SuppressWarnings because generics are explicitly removed from asm binaries
            @SuppressWarnings("unchecked")
            String[] exceptions = Iterables.toArray(mn.exceptions, String.class);
            MethodVisitor mv =
                    cv.visitMethod(mn.access, mn.name, mn.desc, mn.signature, exceptions);
            checkNotNull(mv);
            mn.accept(new RemappingMethodAdapter(mn.access, mn.desc, mv,
                    new SimpleRemapper(cn.name, type.getInternalName())));
        }
    }

    @RequiresNonNull("type")
    private void handleInheritedMethodsThatNowFulfillAdvice(AnalyzedClass analyzedClass) {
        if (analyzedClass.isInterface() || analyzedClass.isAbstract()) {
            return;
        }
        Map<AnalyzedMethodKey, Set<Advice>> matchingAdvisorSets = Maps.newHashMap();
        for (AnalyzedClass superAnalyzedClass : analyzingClassVisitor.getSuperAnalyzedClasses()) {
            if (!superAnalyzedClass.isInterface()) {
                continue;
            }
            for (AnalyzedMethod superAnalyzedMethod : superAnalyzedClass.analyzedMethods()) {
                AnalyzedMethodKey key = AnalyzedMethodKey.wrap(superAnalyzedMethod);
                Set<Advice> matchingAdvisorSet = matchingAdvisorSets.get(key);
                if (matchingAdvisorSet == null) {
                    matchingAdvisorSet = Sets.newHashSet();
                    matchingAdvisorSets.put(key, matchingAdvisorSet);
                }
                matchingAdvisorSet.addAll(superAnalyzedMethod.advisors());
            }
        }
        for (AnalyzedMethod analyzedMethod : analyzedClass.analyzedMethods()) {
            matchingAdvisorSets.remove(AnalyzedMethodKey.wrap(analyzedMethod));
        }
        for (AnalyzedClass superAnalyzedClass : analyzingClassVisitor.getSuperAnalyzedClasses()) {
            if (superAnalyzedClass.isInterface()) {
                continue;
            }
            for (AnalyzedMethod superAnalyzedMethod : superAnalyzedClass.analyzedMethods()) {
                Set<Advice> matchingAdvisorSet =
                        matchingAdvisorSets.get(AnalyzedMethodKey.wrap(superAnalyzedMethod));
                if (matchingAdvisorSet == null) {
                    continue;
                }
                matchingAdvisorSet.removeAll(superAnalyzedMethod.advisors());
            }
        }
        for (Entry<AnalyzedMethodKey, Set<Advice>> entry : matchingAdvisorSets.entrySet()) {
            AnalyzedMethod inheritedMethod = entry.getKey().getAnalyzedMethod();
            Set<Advice> advisors = entry.getValue();
            if (!advisors.isEmpty()) {
                overrideAndWeaveInheritedMethod(analyzedClass, inheritedMethod, advisors);
            }
        }
    }

    @RequiresNonNull("type")
    private void overrideAndWeaveInheritedMethod(AnalyzedClass analyzedClass,
            AnalyzedMethod inheritedMethod, Collection<Advice> matchingAdvisors) {
        String[] exceptions = new String[inheritedMethod.exceptions().size()];
        for (int i = 0; i < inheritedMethod.exceptions().size(); i++) {
            exceptions[i] = ClassNames.toInternalName(inheritedMethod.exceptions().get(i));
        }
        MethodVisitor mv = visitMethodWithAdvice(ACC_PUBLIC, inheritedMethod.name(),
                inheritedMethod.getDesc(), inheritedMethod.signature(), exceptions,
                matchingAdvisors);
        checkNotNull(mv);
        GeneratorAdapter mg = new GeneratorAdapter(mv, ACC_PUBLIC, inheritedMethod.name(),
                inheritedMethod.getDesc());
        mg.visitCode();
        mg.loadThis();
        mg.loadArgs();
        String superName = analyzedClass.superName();
        Type superType;
        if (superName == null) {
            superType = Type.getType(Object.class);
        } else {
            superType = Type.getObjectType(ClassNames.toInternalName(superName));
        }
        // method is called invokeConstructor, but should really be called invokeSpecial
        Method method = new Method(inheritedMethod.name(), inheritedMethod.getDesc());
        mg.invokeConstructor(superType, method);
        mg.returnValue();
        mg.endMethod();
    }

    private static void logInvalidMetricNameWarningOnce(String metricName) {
        if (invalidMetricNameSet.add(metricName)) {
            logger.warn("metric name must contain only letters, digits and spaces: {}",
                    metricName);
        }
    }

    @SuppressWarnings("serial")
    static class ShortCircuitException extends RuntimeException {
        static final ShortCircuitException INSTANCE = new ShortCircuitException();
        private ShortCircuitException() {}
    }

    @SuppressWarnings("serial")
    static class PointcutClassFoundException extends RuntimeException {
        private static final PointcutClassFoundException INSTANCE =
                new PointcutClassFoundException();
        private PointcutClassFoundException() {}
    }

    private static class InitMixins extends AdviceAdapter {

        private final ImmutableList<MixinType> matchedMixinTypes;
        private final Type type;
        private boolean cascadingConstructor;

        InitMixins(MethodVisitor mv, int access, String name, String desc,
                ImmutableList<MixinType> matchedMixinTypes, Type type) {
            super(ASM5, mv, access, name, desc);
            this.matchedMixinTypes = matchedMixinTypes;
            this.type = type;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            if (name.equals("<init>") && owner.equals(type.getInternalName())) {
                cascadingConstructor = true;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (cascadingConstructor) {
                // need to call MixinInit exactly once, so don't call MixinInit at end of cascading
                // constructors
                return;
            }
            for (MixinType mixinType : matchedMixinTypes) {
                String initMethodName = mixinType.initMethodName();
                if (initMethodName != null) {
                    loadThis();
                    invokeVirtual(type, new Method(initMethodName, "()V"));
                }
            }
        }
    }

    private static class MethodMetaGroup {

        private final Type returnType;
        private final List<Type> parameterTypes;
        private final int uniqueNum;
        private final Set<Type> methodMetaTypes;

        private MethodMetaGroup(Type returnType, List<Type> parameterTypes, int uniqueNum,
                Set<Type> methodMetaTypes) {
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
            this.uniqueNum = uniqueNum;
            this.methodMetaTypes = methodMetaTypes;
        }

        private Type getReturnType() {
            return returnType;
        }

        private List<Type> getParameterTypes() {
            return parameterTypes;
        }

        private int getUniqueNum() {
            return uniqueNum;
        }

        private Set<Type> getMethodMetaTypes() {
            return methodMetaTypes;
        }
    }

    // AnalyzedMethod equivalence defined only in terms of method name and parameter types
    // so that overridden methods will be equivalent
    private static class AnalyzedMethodKey {

        private final AnalyzedMethod analyzedMethod;

        public static AnalyzedMethodKey wrap(AnalyzedMethod analyzedMethod) {
            return new AnalyzedMethodKey(analyzedMethod);
        }

        private AnalyzedMethodKey(AnalyzedMethod analyzedMethod) {
            this.analyzedMethod = analyzedMethod;
        }

        private AnalyzedMethod getAnalyzedMethod() {
            return analyzedMethod;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof AnalyzedMethodKey) {
                AnalyzedMethodKey that = (AnalyzedMethodKey) obj;
                return Objects.equal(analyzedMethod.name(), that.analyzedMethod.name())
                        && Objects.equal(analyzedMethod.parameterTypes(),
                                that.analyzedMethod.parameterTypes());
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(analyzedMethod.name(), analyzedMethod.parameterTypes());
        }
    }
}
