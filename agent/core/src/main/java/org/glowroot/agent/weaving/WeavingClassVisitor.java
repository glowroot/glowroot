/*
 * Copyright 2012-2019 the original author or authors.
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
package org.glowroot.agent.weaving;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
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
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.Bytecode;
import org.glowroot.agent.bytecode.api.Util;
import org.glowroot.agent.plugin.api.ClassInfo;
import org.glowroot.agent.plugin.api.MethodInfo;
import org.glowroot.agent.plugin.api.weaving.Shim;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.F_NEW;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INTEGER;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

class WeavingClassVisitor extends ClassVisitor {

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassVisitor.class);

    private static final Type bytecodeType = Type.getType(Bytecode.class);
    private static final Type bytecodeUtilType = Type.getType(Util.class);
    private static final Type classInfoType = Type.getType(ClassInfo.class);
    private static final Type classInfoImplType = Type.getType(ClassInfoImpl.class);
    private static final Type methodInfoType = Type.getType(MethodInfo.class);
    private static final Type methodInfoImplType = Type.getType(MethodInfoImpl.class);

    private static final AtomicLong metaHolderCounter = new AtomicLong();

    private final ClassWriter cw;
    private final @Nullable ClassLoader loader;

    private final boolean frames;
    private final boolean noLongerNeedToWeaveMainMethods;
    private final boolean isClassLoader;
    private final AnalyzedClass analyzedClass;
    private final List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice;

    private final List<ShimType> shimTypes;
    private final List<MixinType> mixinTypes;
    private final List<ClassNode> mixinClassNodes;
    private final Map<String, List<Advice>> methodAdvisors;

    private final AnalyzedWorld analyzedWorld;

    private final Set<String> shimMethods;

    private @MonotonicNonNull Type type;

    // these are for handling class and method metas
    private final Set<Type> classMetaTypes = Sets.newHashSet();
    private final Set<MethodMetaGroup> methodMetaGroups = Sets.newHashSet();
    private @MonotonicNonNull String metaHolderInternalName;
    private int methodMetaCounter;

    private final Set<Advice> usedAdvisors = Sets.newHashSet();

    public WeavingClassVisitor(ClassWriter cw, @Nullable ClassLoader loader, boolean frames,
            boolean noLongerNeedToWeaveMainMethods, AnalyzedClass analyzedClass,
            boolean isClassLoader, List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice,
            List<ShimType> shimTypes, List<MixinType> mixinTypes,
            Map<String, List<Advice>> methodAdvisors, AnalyzedWorld analyzedWorld) {
        super(ASM7, cw);
        this.cw = cw;
        this.loader = loader;
        this.frames = frames;
        this.noLongerNeedToWeaveMainMethods = noLongerNeedToWeaveMainMethods;
        this.isClassLoader = isClassLoader;
        this.analyzedClass = analyzedClass;
        this.methodsThatOnlyNowFulfillAdvice = methodsThatOnlyNowFulfillAdvice;
        this.shimTypes = shimTypes;
        this.mixinTypes = mixinTypes;
        this.methodAdvisors = methodAdvisors;
        this.analyzedWorld = analyzedWorld;

        shimMethods = Sets.newHashSet();
        for (ShimType shimType : shimTypes) {
            for (java.lang.reflect.Method shimMethod : shimType.shimMethods()) {
                Method method = Method.getMethod(shimMethod);
                shimMethods.add(method.getName() + method.getDescriptor());
            }
        }

        // cannot store ClassNode in MixinType and re-use across MethodClassVisitors because
        // MethodNode.accept() cannot be called multiple times (at least not across multiple
        // threads) without throwing an occassional NPE
        mixinClassNodes = Lists.newArrayList();
        if (!analyzedClass.isInterface()) {
            for (MixinType mixinType : mixinTypes) {
                ClassReader cr = new ClassReader(mixinType.implementationBytes());
                ClassNode cn = new ClassNode();
                cr.accept(cn, ClassReader.EXPAND_FRAMES);
                mixinClassNodes.add(cn);
            }
        }
    }

    @Override
    public void visit(int version, int access, String internalName, @Nullable String signature,
            @Nullable String superInternalName,
            String /*@Nullable*/ [] interfaceInternalNamesNullable) {

        type = Type.getObjectType(internalName);
        String[] interfacesPlus = interfaceInternalNamesNullable;
        if (!analyzedClass.isInterface()) {
            // do not add Shim/Mixin interfaces to an interface, otherwise if a lambda implements
            // the interface, it will pick up the inherited Shim/Mixin interfaces, but the
            // Shim/Mixin methods will not be implemented on Java 9+ since lambda classes are not
            // passed to ClassFileTransformer in Java 9+
            interfacesPlus = getInterfacesIncludingShimsAndMixins(interfaceInternalNamesNullable,
                    shimTypes, mixinTypes);
        }
        cw.visit(version, access, internalName, signature, superInternalName, interfacesPlus);
    }

    @Override
    public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
            @Nullable String signature, String /*@Nullable*/ [] exceptions) {
        checkNotNull(type);
        if (isAbstractOrNativeOrSynthetic(access)) {
            // don't try to weave abstract, native and synthetic methods
            // no need to weave bridge methods (which are also marked synthetic) since they forward
            // to non-bridged method which receives same advice (see ClassAnalyzer
            // bridgeTargetAdvisors)
            return cw.visitMethod(access, name, descriptor, signature, exceptions);
        }
        if (isMixinProxy(name, descriptor)) {
            return null;
        }
        if (shimMethods.contains(name + descriptor)) {
            // ignore proxy implementations of shim methods (they will be implemented in visitEnd())
            return null;
        }
        List<Advice> matchingAdvisors = methodAdvisors.get(name + descriptor);
        if (matchingAdvisors == null) {
            matchingAdvisors = ImmutableList.of();
        } else {
            matchingAdvisors = removeSuperseded(matchingAdvisors);
        }
        if (isInitWithMixins(name)) {
            return visitInitWithMixins(access, name, descriptor, signature, exceptions,
                    matchingAdvisors);
        }
        MethodVisitor mv = cw.visitMethod(access, name, descriptor, signature, exceptions);
        if (!noLongerNeedToWeaveMainMethods) {
            if (Modifier.isPublic(access) && Modifier.isStatic(access)
                    && descriptor.equals("([Ljava/lang/String;)V")) {
                if (name.equals("main")) {
                    mv.visitLdcInsn(type.getClassName());
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(),
                            "enteringMainMethod", "(Ljava/lang/String;[Ljava/lang/String;)V",
                            false);
                } else if (name.startsWith("start")) {
                    mv.visitLdcInsn(type.getClassName());
                    mv.visitLdcInsn(name);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(),
                            "enteringPossibleProcrunStartMethod",
                            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", false);
                }
            } else if (type.getInternalName()
                    .equals("org/apache/commons/daemon/support/DaemonLoader")
                    && Modifier.isPublic(access) && Modifier.isStatic(access) && name.equals("load")
                    && descriptor.equals("(Ljava/lang/String;[Ljava/lang/String;)Z")) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(),
                        "enteringApacheCommonsDaemonLoadMethod",
                        "(Ljava/lang/String;[Ljava/lang/String;)V", false);
            }
        }
        if (isClassLoader && name.equals("loadClass")
                && (Modifier.isPublic(access) || Modifier.isProtected(access))
                && !Modifier.isStatic(access)) {
            if (descriptor.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                addLoadClassConditional(mv,
                        new Object[] {type.getInternalName(), "java/lang/String"});
            } else if (descriptor.equals("(Ljava/lang/String;Z)Ljava/lang/Class;")) {
                addLoadClassConditional(mv,
                        new Object[] {type.getInternalName(), "java/lang/String", INTEGER});
            }
        }
        if (matchingAdvisors.isEmpty()) {
            return mv;
        }
        return visitMethodWithAdvice(mv, access, name, descriptor, matchingAdvisors);
    }

    Set<Advice> getUsedAdvisors() {
        return usedAdvisors;
    }

    private boolean isMixinProxy(String name, String descriptor) {
        for (ClassNode cn : mixinClassNodes) {
            List<MethodNode> methodNodes = cn.methods;
            for (MethodNode mn : methodNodes) {
                if (!mn.name.equals("<init>") && mn.name.equals(name)
                        && mn.desc.equals(descriptor)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void visitEnd() {
        checkNotNull(type);
        analyzedWorld.add(analyzedClass, loader);
        if (!analyzedClass.isInterface()) {
            for (ShimType shimType : shimTypes) {
                addShim(shimType);
            }
            for (ClassNode mixinClassNode : mixinClassNodes) {
                addMixin(mixinClassNode);
            }
        }
        for (AnalyzedMethod methodThatOnlyNowFulfillAdvice : methodsThatOnlyNowFulfillAdvice) {
            overrideAndWeaveInheritedMethod(methodThatOnlyNowFulfillAdvice);
        }
        // handle metas at end, since handleInheritedMethodsThatNowFulfillAdvice()
        // above could add new metas
        if (metaHolderInternalName != null) {
            handleMetaHolders();
        }
        cw.visitEnd();
    }

    @RequiresNonNull({"type", "metaHolderInternalName"})
    private void handleMetaHolders() {
        if (loader == null) {
            initializeBoostrapMetaHolders();
        } else {
            try {
                generateMetaHolder();
            } catch (Exception e) {
                // this will terminate weaving and get logged by WeavingClassFileTransformer
                throw new RuntimeException(e);
            }
        }
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
            for (Type methodMetaType : methodMetaGroup.methodMetaTypes()) {
                String methodMetaInternalName = methodMetaType.getInternalName();
                String methodMetaFieldName = "glowroot$method$meta$" + methodMetaGroup.uniqueNum()
                        + '$' + methodMetaInternalName.replace('/', '$');
                BootstrapMetaHolders.createMethodMetaHolder(metaHolderInternalName,
                        methodMetaFieldName, methodMetaType, type, methodMetaGroup.methodName(),
                        methodMetaGroup.methodReturnType(), methodMetaGroup.methodParameterTypes());
            }
        }
    }

    @RequiresNonNull({"type", "metaHolderInternalName", "loader"})
    private void generateMetaHolder() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, metaHolderInternalName, null, "java/lang/Object",
                null);
        Type metaHolderType = Type.getObjectType(metaHolderInternalName);
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        Label l0 = new Label();
        Label l1 = new Label();
        Label l2 = new Label();
        mv.visitTryCatchBlock(l0, l1, l2, "java/lang/Throwable");
        mv.visitLabel(l0);
        for (Type classMetaType : classMetaTypes) {
            String classMetaInternalName = classMetaType.getInternalName();
            String classMetaFieldName =
                    "glowroot$class$meta$" + classMetaInternalName.replace('/', '$');
            FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, classMetaFieldName,
                    "L" + classMetaInternalName + ";", null, null);
            fv.visitEnd();
            mv.visitTypeInsn(NEW, classMetaInternalName);
            mv.visitInsn(DUP);
            mv.visitTypeInsn(NEW, classInfoImplType.getInternalName());
            mv.visitInsn(DUP);
            mv.visitLdcInsn(type.getClassName());
            mv.visitLdcInsn(metaHolderType);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                    "()Ljava/lang/ClassLoader;", false);
            mv.visitMethodInsn(INVOKESPECIAL, classInfoImplType.getInternalName(), "<init>",
                    "(Ljava/lang/String;Ljava/lang/ClassLoader;)V", false);
            mv.visitMethodInsn(INVOKESPECIAL, classMetaInternalName, "<init>",
                    "(L" + classInfoType.getInternalName() + ";)V", false);
            mv.visitFieldInsn(PUTSTATIC, metaHolderInternalName, classMetaFieldName,
                    "L" + classMetaInternalName + ";");
        }
        for (MethodMetaGroup methodMetaGroup : methodMetaGroups) {
            for (Type methodMetaType : methodMetaGroup.methodMetaTypes()) {
                String methodMetaInternalName = methodMetaType.getInternalName();
                String methodMetaFieldName = "glowroot$method$meta$" + methodMetaGroup.uniqueNum()
                        + '$' + methodMetaInternalName.replace('/', '$');
                FieldVisitor fv = cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC,
                        methodMetaFieldName, "L" + methodMetaInternalName + ";", null, null);
                fv.visitEnd();
                mv.visitTypeInsn(NEW, methodMetaInternalName);
                mv.visitInsn(DUP);
                mv.visitTypeInsn(NEW, methodInfoImplType.getInternalName());
                mv.visitInsn(DUP);
                mv.visitLdcInsn(methodMetaGroup.methodName());
                loadType(mv, methodMetaGroup.methodReturnType(), metaHolderType);
                mv.visitTypeInsn(NEW, "java/util/ArrayList");
                mv.visitInsn(DUP);
                List<Type> methodParameterTypes = methodMetaGroup.methodParameterTypes();
                mv.visitLdcInsn(methodParameterTypes.size());
                mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false);
                for (int i = 0; i < methodParameterTypes.size(); i++) {
                    mv.visitInsn(DUP);
                    loadType(mv, methodParameterTypes.get(i), metaHolderType);
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add",
                            "(Ljava/lang/Object;)Z", true);
                    mv.visitInsn(POP);
                }
                mv.visitLdcInsn(type.getClassName());
                mv.visitLdcInsn(metaHolderType);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                        "()Ljava/lang/ClassLoader;", false);
                mv.visitMethodInsn(INVOKESPECIAL, methodInfoImplType.getInternalName(), "<init>",
                        "(Ljava/lang/String;Ljava/lang/Class;Ljava/util/List;Ljava/lang/String;"
                                + "Ljava/lang/ClassLoader;)V",
                        false);
                mv.visitMethodInsn(INVOKESPECIAL, methodMetaInternalName, "<init>",
                        "(L" + methodInfoType.getInternalName() + ";)V", false);
                mv.visitFieldInsn(PUTSTATIC, metaHolderInternalName, methodMetaFieldName,
                        "L" + methodMetaInternalName + ";");
            }
        }
        // catch/log/re-throw
        mv.visitLabel(l1);
        Label l3 = new Label();
        mv.visitJumpInsn(GOTO, l3);
        mv.visitLabel(l2);
        mv.visitVarInsn(ASTORE, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, bytecodeType.getInternalName(), "logThrowable",
                "(Ljava/lang/Throwable;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ATHROW);
        mv.visitLabel(l3);
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
                loadArrayType(mv, type, ownerType);
                break;
            default:
                loadObjectType(mv, type, ownerType);
        }
    }

    private static void loadArrayType(MethodVisitor mv, Type type, Type ownerType) {
        loadType(mv, type.getElementType(), ownerType);
        mv.visitLdcInsn(type.getDimensions());
        mv.visitMethodInsn(INVOKESTATIC, bytecodeUtilType.getInternalName(),
                "getArrayClass", "(Ljava/lang/Class;I)Ljava/lang/Class;", false);
    }

    private static void loadObjectType(MethodVisitor mv, Type type, Type ownerType) {
        // may not have access to type in meta holder (e.g. if type is private), so need to use
        // Class.forName() instead of class constant
        mv.visitLdcInsn(type.getClassName());
        mv.visitInsn(ICONST_0);
        mv.visitLdcInsn(ownerType);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
    }

    private static String /*@Nullable*/ [] getInterfacesIncludingShimsAndMixins(
            String /*@Nullable*/ [] interfaces, List<ShimType> shimTypes,
            List<MixinType> mixinTypes) {
        if (mixinTypes.isEmpty() && shimTypes.isEmpty()) {
            return interfaces;
        }
        Set<String> interfacesIncludingShimsAndMixins = Sets.newHashSet();
        if (interfaces != null) {
            interfacesIncludingShimsAndMixins.addAll(Arrays.asList(interfaces));
        }
        for (ShimType matchedShimType : shimTypes) {
            interfacesIncludingShimsAndMixins.add(matchedShimType.iface().getInternalName());
        }
        for (MixinType matchedMixinType : mixinTypes) {
            for (Type mixinInterface : matchedMixinType.interfaces()) {
                interfacesIncludingShimsAndMixins.add(mixinInterface.getInternalName());
            }
        }
        return Iterables.toArray(interfacesIncludingShimsAndMixins, String.class);
    }

    private boolean isInitWithMixins(String name) {
        return name.equals("<init>") && !mixinTypes.isEmpty();
    }

    @RequiresNonNull("type")
    private MethodVisitor visitInitWithMixins(int access, String name, String descriptor,
            @Nullable String signature, String /*@Nullable*/ [] exceptions,
            List<Advice> matchingAdvisors) {
        MethodVisitor mv = cw.visitMethod(access, name, descriptor, signature, exceptions);
        mv = new InitMixins(mv, access, name, descriptor, mixinTypes, type);
        for (Advice advice : matchingAdvisors) {
            if (!advice.pointcut().timerName().isEmpty()) {
                logger.warn("cannot add timer to <clinit> or <init> methods at this time");
                break;
            }
        }
        return newWeavingMethodVisitor(access, name, descriptor, matchingAdvisors, mv);
    }

    @RequiresNonNull("type")
    private MethodVisitor visitMethodWithAdvice(MethodVisitor mv, int access, String name,
            String descriptor, List<Advice> matchingAdvisors) {
        // FIXME remove superseded advisors
        return newWeavingMethodVisitor(access, name, descriptor, matchingAdvisors, mv);
    }

    @RequiresNonNull("type")
    private WeavingMethodVisitor newWeavingMethodVisitor(int access, String name, String descriptor,
            List<Advice> matchingAdvisors, MethodVisitor mv) {
        Integer methodMetaUniqueNum = collectMetasAtMethod(matchingAdvisors, name, descriptor);
        usedAdvisors.addAll(matchingAdvisors);
        return new WeavingMethodVisitor(mv, frames, access, name, descriptor, type,
                matchingAdvisors, metaHolderInternalName, methodMetaUniqueNum, loader == null);
    }

    private @Nullable Integer collectMetasAtMethod(Iterable<Advice> matchingAdvisors,
            String methodName, String methodDesc) {
        Set<Type> methodMetaTypes = Sets.newHashSet();
        for (Advice matchingAdvice : matchingAdvisors) {
            classMetaTypes.addAll(matchingAdvice.classMetaTypes());
            methodMetaTypes.addAll(matchingAdvice.methodMetaTypes());
        }
        Integer methodMetaUniqueNum = null;
        if (!methodMetaTypes.isEmpty()) {
            methodMetaUniqueNum = ++methodMetaCounter;
            methodMetaGroups.add(ImmutableMethodMetaGroup.builder()
                    .methodName(methodName)
                    .methodReturnType(Type.getReturnType(methodDesc))
                    .addMethodParameterTypes(Type.getArgumentTypes(methodDesc))
                    .uniqueNum(methodMetaUniqueNum)
                    .addAllMethodMetaTypes(methodMetaTypes)
                    .build());
        }
        if ((!classMetaTypes.isEmpty() || !methodMetaTypes.isEmpty())
                && metaHolderInternalName == null) {
            metaHolderInternalName =
                    "org/glowroot/agent/weaving/MetaHolder" + metaHolderCounter.incrementAndGet();
        }
        return methodMetaUniqueNum;
    }

    @RequiresNonNull("type")
    private void addShim(ShimType shimType) {
        for (java.lang.reflect.Method reflectMethod : shimType.shimMethods()) {
            Method method = Method.getMethod(reflectMethod);
            Shim shim = reflectMethod.getAnnotation(Shim.class);
            checkNotNull(shim);
            if (shim.value().length != 1) {
                throw new IllegalStateException(
                        "@Shim annotation must have exactly one value when used on methods");
            }
            Method targetMethod = Method.getMethod(shim.value()[0]);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method.getName(), method.getDescriptor(),
                    null, null);
            mv.visitCode();
            int i = 0;
            mv.visitVarInsn(ALOAD, i++);
            for (Type argumentType : method.getArgumentTypes()) {
                mv.visitVarInsn(argumentType.getOpcode(ILOAD), i++);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, type.getInternalName(), targetMethod.getName(),
                    targetMethod.getDescriptor(), false);
            mv.visitInsn(method.getReturnType().getOpcode(IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    @RequiresNonNull("type")
    private void addMixin(ClassNode mixinClassNode) {
        List<FieldNode> fieldNodes = mixinClassNode.fields;
        for (FieldNode fieldNode : fieldNodes) {
            if (!Modifier.isTransient(fieldNode.access)) {
                // this is needed to avoid serialization issues (even if the new field is
                // serializable, this can still cause issues in a cluster if glowroot is not
                // deployed on all nodes)
                throw new IllegalStateException(
                        "@Mixin fields must be marked transient: " + mixinClassNode.name);
            }
            fieldNode.accept(this);
        }
        List<MethodNode> methodNodes = mixinClassNode.methods;
        for (MethodNode mn : methodNodes) {
            if (mn.name.equals("<init>")) {
                continue;
            }
            String[] exceptions = Iterables.toArray(mn.exceptions, String.class);
            MethodVisitor mv =
                    cw.visitMethod(mn.access, mn.name, mn.desc, mn.signature, exceptions);
            mn.accept(new MethodRemapper(mv,
                    new SimpleRemapper(mixinClassNode.name, type.getInternalName())));
        }
    }

    @RequiresNonNull("type")
    private void overrideAndWeaveInheritedMethod(AnalyzedMethod inheritedMethod) {
        String superName = analyzedClass.superName();
        // superName is null only for java.lang.Object which doesn't inherit anything
        // so safe to assume superName not null here
        checkNotNull(superName);
        String[] exceptions = new String[inheritedMethod.exceptions().size()];
        for (int i = 0; i < inheritedMethod.exceptions().size(); i++) {
            exceptions[i] = ClassNames.toInternalName(inheritedMethod.exceptions().get(i));
        }
        List<Advice> advisors = removeSuperseded(inheritedMethod.advisors());
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, inheritedMethod.name(),
                inheritedMethod.getDesc(), inheritedMethod.signature(), exceptions);
        mv = visitMethodWithAdvice(mv, ACC_PUBLIC, inheritedMethod.name(),
                inheritedMethod.getDesc(), advisors);
        checkNotNull(mv);
        GeneratorAdapter mg = new GeneratorAdapter(mv, ACC_PUBLIC, inheritedMethod.name(),
                inheritedMethod.getDesc());
        mg.visitCode();
        mg.loadThis();
        mg.loadArgs();
        Type superType = Type.getObjectType(ClassNames.toInternalName(superName));
        // method is called invokeConstructor, but should really be called invokeSpecial
        Method method = new Method(inheritedMethod.name(), inheritedMethod.getDesc());
        mg.invokeConstructor(superType, method);
        mg.returnValue();
        mg.endMethod();
    }

    private static List<Advice> removeSuperseded(List<Advice> advisors) {
        if (advisors.size() < 2) {
            // common case optimization
            return advisors;
        }
        Set<String> suppressionKeys = Sets.newHashSet();
        for (Advice advice : advisors) {
            String suppressionKey = advice.pointcut().suppressionKey();
            if (!suppressionKey.isEmpty()) {
                suppressionKeys.add(suppressionKey);
            }
        }
        if (suppressionKeys.isEmpty()) {
            // common case optimization
            return advisors;
        }
        List<Advice> filteredAdvisors = Lists.newArrayList();
        for (Advice advice : advisors) {
            String suppressibleUsingKey = advice.pointcut().suppressibleUsingKey();
            if (suppressibleUsingKey.isEmpty() || !suppressionKeys.contains(suppressibleUsingKey)) {
                filteredAdvisors.add(advice);
            }
        }
        return filteredAdvisors;
    }

    private static boolean isAbstractOrNativeOrSynthetic(int access) {
        return Modifier.isAbstract(access) || Modifier.isNative(access)
                || (access & ACC_SYNTHETIC) != 0;
    }

    private static void addLoadClassConditional(MethodVisitor mv, Object[] locals) {
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, "java/lang/ClassNotFoundException");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("org.glowroot.agent");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z",
                false);
        Label label3 = new Label();
        mv.visitJumpInsn(IFEQ, label3);
        mv.visitLabel(label0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        mv.visitLabel(label1);
        mv.visitInsn(ARETURN);
        mv.visitLabel(label2);
        mv.visitFrame(F_NEW, locals.length, locals, 1,
                new Object[] {"java/lang/ClassNotFoundException"});
        mv.visitInsn(POP);
        mv.visitLabel(label3);
        mv.visitFrame(F_NEW, locals.length, locals, 0, new Object[0]);
    }

    private static class InitMixins extends AdviceAdapter {

        private final ImmutableList<MixinType> matchedMixinTypes;
        private final Type type;
        private boolean cascadingConstructor;

        InitMixins(MethodVisitor mv, int access, String name, String descriptor,
                List<MixinType> matchedMixinTypes, Type type) {
            super(ASM7, mv, access, name, descriptor);
            this.matchedMixinTypes = ImmutableList.copyOf(matchedMixinTypes);
            this.type = type;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean itf) {
            if (name.equals("<init>") && owner.equals(type.getInternalName())) {
                cascadingConstructor = true;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
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

    @Value.Immutable
    interface MethodMetaGroup {
        String methodName();
        Type methodReturnType();
        ImmutableList<Type> methodParameterTypes();
        int uniqueNum();
        ImmutableSet<Type> methodMetaTypes();
    }
}
