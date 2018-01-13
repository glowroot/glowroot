/*
 * Copyright 2012-2018 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
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

import org.glowroot.agent.plugin.api.weaving.Shim;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ASM6;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.F_SAME;
import static org.objectweb.asm.Opcodes.F_SAME1;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

class WeavingClassVisitor extends ClassVisitor {

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassVisitor.class);

    private static final AtomicLong metaHolderCounter = new AtomicLong();

    private final ClassWriter cw;
    private final @Nullable ClassLoader loader;

    private final AnalyzedClass analyzedClass;
    private final List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice;

    private final List<ShimType> shimTypes;
    private final List<MixinType> mixinTypes;
    private final List<ClassNode> mixinClassNodes;
    private final Map<String, List<Advice>> methodAdvisors;

    private final AnalyzedWorld analyzedWorld;

    private @MonotonicNonNull Type type;

    // these are for handling class and method metas
    private final Set<Type> classMetaTypes = Sets.newHashSet();
    private final Set<MethodMetaGroup> methodMetaGroups = Sets.newHashSet();
    private @MonotonicNonNull String metaHolderInternalName;
    private int methodMetaCounter;

    public WeavingClassVisitor(ClassWriter cw, @Nullable ClassLoader loader,
            AnalyzedClass analyzedClass, List<AnalyzedMethod> methodsThatOnlyNowFulfillAdvice,
            List<ShimType> shimTypes, List<MixinType> mixinTypes,
            Map<String, List<Advice>> methodAdvisors, AnalyzedWorld analyzedWorld) {
        super(ASM6, cw);
        this.cw = cw;
        this.loader = loader;
        this.analyzedClass = analyzedClass;
        this.methodsThatOnlyNowFulfillAdvice = methodsThatOnlyNowFulfillAdvice;
        this.shimTypes = shimTypes;
        this.mixinTypes = mixinTypes;
        this.methodAdvisors = methodAdvisors;
        this.analyzedWorld = analyzedWorld;

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
        String /*@Nullable*/ [] interfacesIncludingMixins = getInterfacesIncludingShimsAndMixins(
                interfaceInternalNamesNullable, shimTypes, mixinTypes);
        cw.visit(version, access, internalName, signature, superInternalName,
                interfacesIncludingMixins);
    }

    @Override
    public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/ [] exceptions) {
        checkNotNull(type);
        if (isAbstractOrNativeOrSynthetic(access)) {
            // don't try to weave abstract, native and synthetic methods
            // no need to weave bridge methods (which are also marked synthetic) since they forward
            // to non-bridged method which receives same advice (see ClassAnalyzer
            // bridgeTargetAdvisors)
            return cw.visitMethod(access, name, desc, signature, exceptions);
        }
        if (isMixinProxy(name, desc)) {
            return null;
        }
        List<Advice> matchingAdvisors = methodAdvisors.get(name + desc);
        if (matchingAdvisors == null) {
            matchingAdvisors = ImmutableList.of();
        } else {
            matchingAdvisors = removeSuperseded(matchingAdvisors);
        }
        if (isInitWithMixins(name)) {
            return visitInitWithMixins(access, name, desc, signature, exceptions, matchingAdvisors);
        }
        if (matchingAdvisors.isEmpty()) {
            return cw.visitMethod(access, name, desc, signature, exceptions);
        }
        return visitMethodWithAdvice(access, name, desc, signature, exceptions, matchingAdvisors);
    }

    private boolean isMixinProxy(String name, String desc) {
        for (ClassNode cn : mixinClassNodes) {
            List<MethodNode> methodNodes = cn.methods;
            for (MethodNode mn : methodNodes) {
                if (!mn.name.equals("<init>") && mn.name.equals(name) && mn.desc.equals(desc)) {
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
        if (!analyzedClass.isAbstract()) {
            for (AnalyzedMethod methodThatOnlyNowFulfillAdvice : methodsThatOnlyNowFulfillAdvice) {
                overrideAndWeaveInheritedMethod(methodThatOnlyNowFulfillAdvice);
            }
        }
        // handle metas at end, since handleInheritedMethodsThatNowFulfillAdvice()
        // above could add new metas
        handleMetaHolders();
        cw.visitEnd();
    }

    @RequiresNonNull("type")
    private void handleMetaHolders() {
        if (metaHolderInternalName != null) {
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
                        methodMetaGroup.methodParameterTypes());
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
        mv.visitTryCatchBlock(l0, l1, l2, "java/lang/ClassNotFoundException");
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
            loadType(mv, type, metaHolderType);
            mv.visitMethodInsn(INVOKESPECIAL, classMetaInternalName, "<init>",
                    "(Ljava/lang/Class;)V", false);
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
                loadType(mv, type, metaHolderType);
                mv.visitLdcInsn(methodMetaGroup.methodName());
                mv.visitLdcInsn(methodMetaGroup.methodParameterTypes().size());
                mv.visitTypeInsn(ANEWARRAY, "java/lang/Class");
                for (int i = 0; i < methodMetaGroup.methodParameterTypes().size(); i++) {
                    mv.visitInsn(DUP);
                    mv.visitLdcInsn(i);
                    loadType(mv, methodMetaGroup.methodParameterTypes().get(i), metaHolderType);
                    mv.visitInsn(AASTORE);
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
                        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
                mv.visitMethodInsn(INVOKESPECIAL, methodMetaInternalName, "<init>",
                        "(Ljava/lang/reflect/Method;)V", false);
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
                loadArrayType(mv, type, ownerType);
                break;
            default:
                loadObjectType(mv, type, ownerType);
        }
    }

    private static void loadArrayType(MethodVisitor mv, Type type, Type ownerType) {
        loadType(mv, type.getElementType(), ownerType);
        mv.visitLdcInsn(type.getDimensions());
        mv.visitMethodInsn(INVOKESTATIC, Type.getInternalName(GeneratedBytecodeUtil.class),
                "getArrayClass", "(Ljava/lang/Class;I)Ljava/lang/Class;", false);
    }

    private static void loadObjectType(MethodVisitor mv, Type type, Type ownerType) {
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
    private MethodVisitor visitInitWithMixins(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/ [] exceptions,
            List<Advice> matchingAdvisors) {
        Integer methodMetaUniqueNum = collectMetasAtMethod(matchingAdvisors, name, desc);
        MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
        mv = new InitMixins(mv, access, name, desc, mixinTypes, type);
        for (Advice advice : matchingAdvisors) {
            if (!advice.pointcut().timerName().isEmpty()) {
                logger.warn("cannot add timer to <clinit> or <init> methods at this time");
                break;
            }
        }
        return new WeavingMethodVisitor(mv, access, name, desc, type, matchingAdvisors,
                metaHolderInternalName, methodMetaUniqueNum, loader == null, null);
    }

    @RequiresNonNull("type")
    private MethodVisitor visitMethodWithAdvice(int access, String name, String desc,
            @Nullable String signature, String /*@Nullable*/ [] exceptions,
            Iterable<Advice> matchingAdvisors) {
        // FIXME remove superseded advisors
        Integer methodMetaUniqueNum = collectMetasAtMethod(matchingAdvisors, name, desc);
        MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
        return new WeavingMethodVisitor(mv, access, name, desc, type, matchingAdvisors,
                metaHolderInternalName, methodMetaUniqueNum, loader == null, null);
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
            List<Type> parameterTypes = Arrays.asList(Type.getArgumentTypes(methodDesc));
            methodMetaGroups.add(ImmutableMethodMetaGroup.builder()
                    .methodName(methodName)
                    .addAllMethodParameterTypes(parameterTypes)
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
        MethodVisitor mv = visitMethodWithAdvice(ACC_PUBLIC, inheritedMethod.name(),
                inheritedMethod.getDesc(), inheritedMethod.signature(), exceptions, advisors);
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

    private static class InitMixins extends AdviceAdapter {

        private final ImmutableList<MixinType> matchedMixinTypes;
        private final Type type;
        private boolean cascadingConstructor;

        InitMixins(MethodVisitor mv, int access, String name, String desc,
                List<MixinType> matchedMixinTypes, Type type) {
            super(ASM6, mv, access, name, desc);
            this.matchedMixinTypes = ImmutableList.copyOf(matchedMixinTypes);
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

    @Value.Immutable
    interface MethodMetaGroup {
        String methodName();
        ImmutableList<Type> methodParameterTypes();
        int uniqueNum();
        ImmutableSet<Type> methodMetaTypes();
    }
}
