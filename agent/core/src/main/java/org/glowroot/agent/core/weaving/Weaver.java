/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.core.weaving;

import java.io.IOException;
import java.security.CodeSource;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.core.weaving.AnalyzedWorld.ParseContext;
import org.glowroot.agent.core.weaving.AnalyzingClassVisitor.ShortCircuitException;
import org.glowroot.agent.core.weaving.WeavingClassVisitor.PointcutClassFoundException;
import org.glowroot.agent.core.weaving.WeavingTimerService.WeavingTimer;

import static org.objectweb.asm.Opcodes.ASM5;

class Weaver {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    private final Supplier<List<Advice>> advisors;
    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;
    private final AnalyzedWorld analyzedWorld;
    private final WeavingTimerService weavingTimerService;
    private final boolean timerWrapperMethods;

    Weaver(Supplier<List<Advice>> advisors, List<ShimType> shimTypes, List<MixinType> mixinTypes,
            AnalyzedWorld analyzedWorld, WeavingTimerService weavingTimerService,
            boolean timerWrapperMethods) {
        this.advisors = advisors;
        this.shimTypes = ImmutableList.copyOf(shimTypes);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.analyzedWorld = analyzedWorld;
        this.weavingTimerService = weavingTimerService;
        this.timerWrapperMethods = timerWrapperMethods;
    }

    byte /*@Nullable*/[] weave(byte[] classBytes, String className, @Nullable CodeSource codeSource,
            @Nullable ClassLoader loader) {
        if (timerWrapperMethods) {
            return weave$glowroot$timer$glowroot$weaving$0(classBytes, className, codeSource,
                    loader);
        } else {
            return weaveInternal(classBytes, className, codeSource, loader);
        }
    }

    // weird method name is following "timer marker" method naming
    private byte /*@Nullable*/[] weave$glowroot$timer$glowroot$weaving$0(byte[] classBytes,
            String className, @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        return weaveInternal(classBytes, className, codeSource, loader);
    }

    private byte /*@Nullable*/[] weaveInternal(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        WeavingTimer weavingTimer = weavingTimerService.start();
        try {
            return weaveUnderTimer(classBytes, className, codeSource, loader);
        } finally {
            weavingTimer.stop();
        }
    }

    private byte/*@Nullable*/[] weaveUnderTimer(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
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
        WeavingClassVisitor cv = new WeavingClassVisitor(cw, advisors.get(), shimTypes, mixinTypes,
                loader, analyzedWorld, codeSource, timerWrapperMethods);
        ClassReader cr = new ClassReader(classBytes);
        boolean shortCircuitException = false;
        boolean pointcutClassFoundException = false;
        try {
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.SKIP_FRAMES);
        } catch (ShortCircuitException e) {
            shortCircuitException = true;
        } catch (PointcutClassFoundException e) {
            pointcutClassFoundException = true;
        }
        if (shortCircuitException || cv.isInterfaceSoNothingToWeave()) {
            return null;
        } else if (pointcutClassFoundException) {
            ClassWriter cw2 = new ComputeFramesClassWriter(ClassWriter.COMPUTE_FRAMES,
                    analyzedWorld, loader, codeSource, className);
            PointcutClassVisitor cv2 = new PointcutClassVisitor(cw2);
            ClassReader cr2 = new ClassReader(classBytes);
            cr2.accept(new JSRInlinerClassVisitor(cv2), ClassReader.SKIP_FRAMES);
            return cw2.toByteArray();
        } else {
            return cw.toByteArray();
        }
    }

    private static class JSRInlinerClassVisitor extends ClassVisitor {

        private final ClassVisitor cv;

        private JSRInlinerClassVisitor(ClassVisitor cv) {
            super(ASM5, cv);
            this.cv = cv;
        }

        @Override
        @Nullable
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String/*@Nullable*/[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
        }
    }

    @VisibleForTesting
    static class ComputeFramesClassWriter extends ClassWriter {

        private final AnalyzedWorld analyzedWorld;
        @Nullable
        private final ClassLoader loader;
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
}
