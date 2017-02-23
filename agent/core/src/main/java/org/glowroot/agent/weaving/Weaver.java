/*
 * Copyright 2012-2017 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.CodeSource;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.ThreadContextImpl;
import org.glowroot.agent.impl.TimerImpl;
import org.glowroot.agent.impl.TimerNameCache;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.weaving.AnalyzedWorld.ParseContext;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM5;

public class Weaver {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    // useful for debugging java.lang.VerifyErrors
    private static final boolean VERIFY_WEAVING = Boolean.getBoolean("glowroot.weaving.verify");

    private final Supplier<List<Advice>> advisors;
    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;
    private final AnalyzedWorld analyzedWorld;
    private final TransactionRegistry transactionRegistry;
    private final TimerName timerName;

    private volatile boolean enabled;

    public Weaver(Supplier<List<Advice>> advisors, List<ShimType> shimTypes,
            List<MixinType> mixinTypes, AnalyzedWorld analyzedWorld,
            TransactionRegistry transactionRegistry, TimerNameCache timerNameCache,
            final ConfigService configService) {
        this.advisors = advisors;
        this.shimTypes = ImmutableList.copyOf(shimTypes);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.analyzedWorld = analyzedWorld;
        this.transactionRegistry = transactionRegistry;
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                enabled = configService.getAdvancedConfig().weavingTimer();
            }
        });
        this.timerName = timerNameCache.getTimerName(OnlyForTheTimerName.class);
    }

    byte /*@Nullable*/[] weave(byte[] classBytes, String className, @Nullable CodeSource codeSource,
            @Nullable ClassLoader loader) {
        TimerImpl weavingTimer = startWeavingTimer();
        try {
            logger.trace("transform(): className={}", className);
            byte[] transformedBytes = weaveUnderTimer(classBytes, className, codeSource, loader);
            if (transformedBytes != null) {
                logger.debug("transform(): transformed {}", className);
            }
            return transformedBytes;
        } finally {
            if (weavingTimer != null) {
                weavingTimer.stop();
            }
        }
    }

    private @Nullable TimerImpl startWeavingTimer() {
        if (!enabled) {
            return null;
        }
        ThreadContextImpl threadContext = transactionRegistry.getCurrentThreadContextHolder().get();
        if (threadContext == null) {
            return null;
        }
        TimerImpl currentTimer = threadContext.getCurrentTimer();
        if (currentTimer == null) {
            return null;
        }
        return currentTimer.startNestedTimer(timerName);
    }

    private byte/*@Nullable*/[] weaveUnderTimer(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        List<Advice> advisors = analyzedWorld.mergeInstrumentationAnnotations(this.advisors.get(),
                classBytes, loader, className);
        ThinClassVisitor accv = new ThinClassVisitor();
        new ClassReader(classBytes).accept(accv, ClassReader.SKIP_FRAMES + ClassReader.SKIP_CODE);
        byte[] maybeFelixBytes = null;
        if (className.equals("org/apache/felix/framework/BundleWiringImpl")) {
            ClassWriter cw = new ComputeFramesClassWriter(ClassWriter.COMPUTE_FRAMES, analyzedWorld,
                    loader, codeSource, className);
            ClassVisitor cv = new FelixOsgiHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.SKIP_FRAMES);
            maybeFelixBytes = cw.toByteArray();
        }
        ClassAnalyzer classAnalyzer = new ClassAnalyzer(accv.getThinClass(), advisors, shimTypes,
                mixinTypes, loader, analyzedWorld, codeSource, classBytes);
        classAnalyzer.analyzeMethods();
        if (!classAnalyzer.isWeavingRequired()) {
            analyzedWorld.add(classAnalyzer.getAnalyzedClass(), loader);
            return maybeFelixBytes;
        }
        // from http://www.oracle.com/technetwork/java/javase/compatibility-417013.html:
        //
        // "Classfiles with version number 51 are exclusively verified using the type-checking
        // verifier, and thus the methods must have StackMapTable attributes when appropriate.
        // For classfiles with version 50, the Hotspot JVM would (and continues to) failover to
        // the type-inferencing verifier if the stackmaps in the file were missing or incorrect.
        // This failover behavior does not occur for classfiles with version 51 (the default
        // version for Java SE 7).
        // Any tool that modifies bytecode in a version 51 classfile must be sure to update the
        // stackmap information to be consistent with the bytecode in order to pass
        // verification."
        //
        ClassWriter cw = new ComputeFramesClassWriter(ClassWriter.COMPUTE_FRAMES, analyzedWorld,
                loader, codeSource, className);
        WeavingClassVisitor cv =
                new WeavingClassVisitor(cw, loader, classAnalyzer.getAnalyzedClass(),
                        classAnalyzer.getMethodsThatOnlyNowFulfillAdvice(),
                        classAnalyzer.getMatchedShimTypes(), classAnalyzer.getMatchedMixinTypes(),
                        classAnalyzer.getMethodAdvisors(), analyzedWorld);
        ClassReader cr = new ClassReader(maybeFelixBytes == null ? classBytes : maybeFelixBytes);
        try {
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            logger.error("unable to weave {}: {}", className, e.getMessage(), e);
            try {
                File tempFile = getTempFile(className, "glowroot-weaving-error-", ".class");
                Files.write(classBytes, tempFile);
                logger.error("wrote bytecode to: {}", tempFile.getAbsolutePath());
            } catch (IOException f) {
                logger.error(f.getMessage(), f);
            }
            return null;
        }
        byte[] transformedBytes = cw.toByteArray();
        if (VERIFY_WEAVING) {
            verify(transformedBytes, loader, classBytes, className);
        }
        return transformedBytes;
    }

    private static void verify(byte[] transformedBytes, @Nullable ClassLoader loader,
            byte[] originalBytes, String className) {
        String originalBytesVerifyError = verify(originalBytes, loader);
        if (!originalBytesVerifyError.isEmpty()) {
            // not much to do if original byte code fails to verify
            logger.debug("class verify error for original bytecode\n:" + originalBytesVerifyError);
            return;
        }
        String transformedBytesVerifyError = verify(transformedBytes, loader);
        if (!transformedBytesVerifyError.isEmpty()) {
            logger.error(
                    "class verify error for transformed bytecode\n:" + transformedBytesVerifyError);
            try {
                File originalBytesFile =
                        getTempFile(className, "glowroot-verify-error-", "-original.class");
                Files.write(originalBytes, originalBytesFile);
                logger.error("wrote original bytecode to: {}", originalBytesFile.getAbsolutePath());
                File transformedBytesFile =
                        getTempFile(className, "glowroot-verify-error-", "-transformed.class");
                Files.write(transformedBytes, transformedBytesFile);
                logger.error("wrote transformed bytecode to: {}",
                        transformedBytesFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private static String verify(byte[] bytes, @Nullable ClassLoader loader) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        CheckClassAdapter.verify(new ClassReader(bytes), loader, false, pw);
        pw.close();
        return sw.toString();
    }

    private static File getTempFile(String className, String prefix, String suffix) {
        String tmpDirProperty = StandardSystemProperty.JAVA_IO_TMPDIR.value();
        File tmpDir = tmpDirProperty == null ? new File(".") : new File(tmpDirProperty);
        String simpleName;
        int index = className.lastIndexOf('/');
        if (index == -1) {
            simpleName = className;
        } else {
            simpleName = className.substring(index + 1);
        }
        return new File(tmpDir, prefix + simpleName + suffix);
    }

    private static class JSRInlinerClassVisitor extends ClassVisitor {

        private JSRInlinerClassVisitor(ClassVisitor cv) {
            super(ASM5, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String/*@Nullable*/[] exceptions) {
            MethodVisitor mv =
                    checkNotNull(cv).visitMethod(access, name, desc, signature, exceptions);
            return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
        }
    }

    @VisibleForTesting
    static class ComputeFramesClassWriter extends ClassWriter {

        private final AnalyzedWorld analyzedWorld;
        private final @Nullable ClassLoader loader;
        private final ParseContext parseContext;

        public ComputeFramesClassWriter(int flags, AnalyzedWorld analyzedWorld,
                @Nullable ClassLoader loader, @Nullable CodeSource codeSource, String className) {
            super(flags);
            this.analyzedWorld = analyzedWorld;
            this.loader = loader;
            this.parseContext = ImmutableParseContext.of(className, codeSource);
        }

        // implements logic similar to org.objectweb.asm.ClassWriter.getCommonSuperClass()
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) {
                return "java/lang/Object";
            }
            try {
                return getCommonSuperClassInternal(type1, type2);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return "java/lang/Object";
            }
        }

        private String getCommonSuperClassInternal(String type1, String type2) throws IOException {
            AnalyzedClass analyzedClass1;
            try {
                analyzedClass1 =
                        analyzedWorld.getAnalyzedClass(ClassNames.fromInternalName(type1), loader);
            } catch (ClassNotFoundException e) {
                // log at debug level only since this code will fail anyways if it is actually used
                // at runtime since type doesn't exist
                logger.debug("type {} not found while parsing type {}", type1, parseContext, e);
                return "java/lang/Object";
            }
            AnalyzedClass analyzedClass2;
            try {
                analyzedClass2 =
                        analyzedWorld.getAnalyzedClass(ClassNames.fromInternalName(type2), loader);
            } catch (ClassNotFoundException e) {
                // log at debug level only since this code will fail anyways if it is actually used
                // at runtime since type doesn't exist
                logger.debug("type {} not found while parsing type {}", type2, parseContext, e);
                return "java/lang/Object";
            }
            return getCommonSuperClass(analyzedClass1, analyzedClass2, type1, type2);
        }

        private String getCommonSuperClass(AnalyzedClass analyzedClass1,
                AnalyzedClass analyzedClass2, String type1, String type2) throws IOException {
            if (isAssignableFrom(analyzedClass1.name(), analyzedClass2)) {
                return type1;
            }
            if (isAssignableFrom(analyzedClass2.name(), analyzedClass1)) {
                return type2;
            }
            if (analyzedClass1.isInterface() || analyzedClass2.isInterface()) {
                return "java/lang/Object";
            }
            return getCommonSuperClass(analyzedClass1, analyzedClass2);
        }

        private String getCommonSuperClass(AnalyzedClass analyzedClass1,
                AnalyzedClass analyzedClass2) throws IOException {
            // climb analyzedClass1 super class hierarchy and check if any of them are assignable
            // from analyzedClass2
            String superName = analyzedClass1.superName();
            while (superName != null) {
                if (isAssignableFrom(superName, analyzedClass2)) {
                    return ClassNames.toInternalName(superName);
                }
                try {
                    AnalyzedClass superAnalyzedClass =
                            analyzedWorld.getAnalyzedClass(superName, loader);
                    superName = superAnalyzedClass.superName();
                } catch (ClassNotFoundException e) {
                    // log at debug level only since this code must not be getting used anyways, as
                    // it would fail on execution since the type doesn't exist
                    logger.debug("type {} not found while parsing type {}", superName, parseContext,
                            e);
                    return "java/lang/Object";
                }
            }
            return "java/lang/Object";
        }

        private boolean isAssignableFrom(String possibleSuperClassName, AnalyzedClass analyzedClass)
                throws IOException {
            if (analyzedClass.name().equals(possibleSuperClassName)) {
                return true;
            }
            if (isAssignableFromInterfaces(possibleSuperClassName, analyzedClass)) {
                return true;
            }
            String superName = analyzedClass.superName();
            if (superName == null) {
                return false;
            }
            return isAssignableFromSuperClass(possibleSuperClassName, superName);
        }

        private boolean isAssignableFromInterfaces(String possibleSuperClassName,
                AnalyzedClass analyzedClass) throws IOException {
            for (String interfaceName : analyzedClass.interfaceNames()) {
                try {
                    AnalyzedClass interfaceAnalyzedClass =
                            analyzedWorld.getAnalyzedClass(interfaceName, loader);
                    if (isAssignableFrom(possibleSuperClassName, interfaceAnalyzedClass)) {
                        return true;
                    }
                } catch (ClassNotFoundException e) {
                    // log at debug level only since this code must not be getting used anyways, as
                    // it would fail on execution since the type doesn't exist
                    logger.debug("type {} not found while parsing type {}", interfaceName,
                            parseContext, e);
                }
            }
            return false;
        }

        private boolean isAssignableFromSuperClass(String possibleSuperClassName, String superName)
                throws IOException {
            try {
                AnalyzedClass superAnalyzedClass =
                        analyzedWorld.getAnalyzedClass(superName, loader);
                return isAssignableFrom(possibleSuperClassName, superAnalyzedClass);
            } catch (ClassNotFoundException e) {
                // log at debug level only since this code must not be getting used anyways, as it
                // would fail on execution since the type doesn't exist
                logger.debug("type {} not found while parsing type {}", superName, parseContext, e);
                return false;
            }
        }
    }

    private static class FelixOsgiHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        FelixOsgiHackClassVisitor(ClassWriter cw) {
            super(ASM5, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/[] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("shouldBootDelegate") && desc.equals("(Ljava/lang/String;)Z")) {
                return new FelixOsgiHackMethodVisitor(mv, access, name, desc);
            } else {
                return mv;
            }
        }
    }

    private static class FelixOsgiHackMethodVisitor extends AdviceAdapter {

        private FelixOsgiHackMethodVisitor(MethodVisitor mv, int access, String name, String desc) {
            super(ASM5, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitLdcInsn("org.glowroot.");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            Label label = new Label();
            mv.visitJumpInsn(IFEQ, label);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(label);

        }
    }

    @Pointcut(className = "", methodName = "", methodParameterTypes = {},
            timerName = "glowroot weaving")
    private static class OnlyForTheTimerName {
        private OnlyForTheTimerName() {}
    }
}
