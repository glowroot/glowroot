/*
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
package org.glowroot.weaving;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.CodeSource;

import checkers.nullness.quals.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import dataflow.quals.Pure;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.MetricName;
import org.glowroot.api.MetricTimer;
import org.glowroot.markers.ThreadSafe;
import org.glowroot.weaving.ParsedTypeCache.ParseContext;

import static org.objectweb.asm.Opcodes.ASM4;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class Weaver {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    // this is an internal property sometimes useful for debugging errors in the weaver,
    // especially exceptions of type java.lang.VerifyError
    private static final boolean verifyWeaving =
            Boolean.valueOf(System.getProperty("glowroot.internal.weaving.verify"));

    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> pluginAdvisors;
    private final Supplier<ImmutableList<Advice>> pointcutConfigAdvisors;
    private final ParsedTypeCache parsedTypeCache;
    private final MetricTimerService metricTimerService;
    private final boolean generateMetricNameWrapperMethods;

    private final MetricName weavingMetricName;

    Weaver(ImmutableList<MixinType> mixinTypes, ImmutableList<Advice> pluginAdvisors,
            Supplier<ImmutableList<Advice>> pointcutConfigAdvisors,
            ParsedTypeCache parsedTypeCache, MetricTimerService metricTimerService,
            boolean generateMetricNameWrapperMethods) {
        this.mixinTypes = mixinTypes;
        this.pluginAdvisors = pluginAdvisors;
        this.pointcutConfigAdvisors = pointcutConfigAdvisors;
        this.parsedTypeCache = parsedTypeCache;
        this.metricTimerService = metricTimerService;
        this.generateMetricNameWrapperMethods = generateMetricNameWrapperMethods;
        weavingMetricName = metricTimerService.getMetricName("glowroot weaving");
    }

    byte/*@Nullable*/[] weave(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        if (generateMetricNameWrapperMethods) {
            return weave$glowroot$metric$glowroot$weaving$0(classBytes, className, codeSource,
                    loader);
        } else {
            return weaveInternal(classBytes, className, codeSource, loader);
        }
    }

    // weird method name is following "metric marker" method naming
    private byte/*@Nullable*/[] weave$glowroot$metric$glowroot$weaving$0(byte[] classBytes,
            String className, @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        return weaveInternal(classBytes, className, codeSource, loader);
    }

    private byte/*@Nullable*/[] weaveInternal(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        MetricTimer metricTimer = metricTimerService.startMetricTimer(weavingMetricName);
        try {
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
            ClassWriter cw = new ComputeFramesClassWriter(
                    ClassWriter.COMPUTE_MAXS + ClassWriter.COMPUTE_FRAMES,
                    parsedTypeCache, loader, codeSource, className);
            Iterable<Advice> advisors = Iterables.concat(pluginAdvisors,
                    pointcutConfigAdvisors.get());
            WeavingClassVisitor cv = new WeavingClassVisitor(cw, mixinTypes, advisors, loader,
                    parsedTypeCache, codeSource, generateMetricNameWrapperMethods);
            ClassReader cr = new ClassReader(classBytes);
            try {
                cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.SKIP_FRAMES);
            } catch (ClassCircularityError e) {
                logger.error(e.getMessage(), e);
                return null;
            }
            if (cv.isNothingAtAllToWeave()) {
                return null;
            } else {
                byte[] wovenBytes = cw.toByteArray();
                if (verifyWeaving) {
                    verifyBytecode(classBytes, className, false);
                    verifyBytecode(wovenBytes, className, true);
                }
                return wovenBytes;
            }
        } finally {
            metricTimer.stop();
        }
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("mixinTypes", mixinTypes)
                .add("pluginAdvisors", pluginAdvisors)
                .add("pointcutConfigAdvisors", pointcutConfigAdvisors)
                .add("parsedTypeCache", parsedTypeCache)
                .toString();
    }

    private static void verifyBytecode(byte[] bytes, String className, boolean woven) {
        ClassReader verifyClassReader = new ClassReader(bytes);
        try {
            CheckClassAdapter.verify(verifyClassReader, false, new PrintWriter(System.err));
        } catch (Exception e) {
            if (woven) {
                logger.warn("error verifying class {} (after weaving): {}", className,
                        e.getMessage(), e);
            } else {
                logger.warn("error verifying class {} (before weaving): {}", className,
                        e.getMessage(), e);
            }
        }
    }

    private static class JSRInlinerClassVisitor extends ClassVisitor {

        private JSRInlinerClassVisitor(ClassVisitor cv) {
            super(ASM4, cv);
        }

        @Override
        @Nullable
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String/*@Nullable*/[] exceptions) {
            if (cv != null) {
                MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
                return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
            }
            return null;
        }
    }

    @VisibleForTesting
    static class ComputeFramesClassWriter extends ClassWriter {

        private final ParsedTypeCache parsedTypeCache;
        @Nullable
        private final ClassLoader loader;
        private final ParseContext parseContext;

        public ComputeFramesClassWriter(int flags, ParsedTypeCache parsedTypeCache,
                @Nullable ClassLoader loader, @Nullable CodeSource codeSource, String className) {
            super(flags);
            this.parsedTypeCache = parsedTypeCache;
            this.loader = loader;
            this.parseContext = new ParseContext(className, codeSource);
        }

        // implements logic similar to org.objectweb.asm.ClassWriter.getCommonSuperClass()
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) {
                return "java/lang/Object";
            }
            ParsedType parsedType1;
            try {
                parsedType1 = parsedTypeCache.getParsedType(TypeNames.fromInternal(type1), loader);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return "java/lang/Object";
            } catch (ClassNotFoundException e) {
                // log at debug level only since this code will fail anyways if it is actually used
                // at runtime since type doesn't exist
                logger.debug("type {} not found while parsing type {}", type1, parseContext, e);
                return "java/lang/Object";
            }
            ParsedType parsedType2;
            try {
                parsedType2 = parsedTypeCache.getParsedType(TypeNames.fromInternal(type2), loader);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return "java/lang/Object";
            } catch (ClassNotFoundException e) {
                // log at debug level only since this code must not be getting used anyways, as it
                // would fail on execution since the type doesn't exist
                logger.debug("type {} not found while parsing type {}", type2, parseContext, e);
                return "java/lang/Object";
            }
            return getCommonSuperClass(parsedType1, parsedType2, type1, type2);
        }

        private String getCommonSuperClass(ParsedType parsedType1, ParsedType parsedType2,
                String type1, String type2) {
            if (isAssignableFrom(parsedType1.getName(), parsedType2)) {
                return type1;
            }
            if (isAssignableFrom(parsedType2.getName(), parsedType1)) {
                return type2;
            }
            if (parsedType1.isInterface() || parsedType2.isInterface()) {
                return "java/lang/Object";
            }
            // climb parsedType1 super class hierarchy and check if any of them are assignable from
            // parsedType2
            String superName = parsedType1.getSuperName();
            while (superName != null) {
                if (isAssignableFrom(superName, parsedType2)) {
                    return TypeNames.toInternal(superName);
                }
                try {
                    ParsedType superParsedType = parsedTypeCache.getParsedType(superName, loader);
                    superName = superParsedType.getSuperName();
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                    return "java/lang/Object";
                } catch (ClassNotFoundException e) {
                    // log at debug level only since this code must not be getting used anyways, as
                    // it would fail on execution since the type doesn't exist
                    logger.debug("type {} not found while parsing type {}", superName,
                            parseContext);
                    return "java/lang/Object";
                }
            }
            return "java/lang/Object";
        }

        private boolean isAssignableFrom(String possibleSuperTypeName, ParsedType parsedType) {
            if (parsedType.getName().equals(possibleSuperTypeName)) {
                return true;
            }
            for (String interfaceName : parsedType.getInterfaceNames()) {
                try {
                    ParsedType interfaceParsedType =
                            parsedTypeCache.getParsedType(interfaceName, loader);
                    if (isAssignableFrom(possibleSuperTypeName, interfaceParsedType)) {
                        return true;
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                } catch (ClassNotFoundException e) {
                    // log at debug level only since this code must not be getting used anyways, as
                    // it would fail on execution since the type doesn't exist
                    logger.debug("type {} not found while parsing type {}", interfaceName,
                            parseContext);
                }
            }
            String superName = parsedType.getSuperName();
            if (superName == null) {
                return false;
            }
            try {
                ParsedType superParsedType = parsedTypeCache.getParsedType(superName, loader);
                return isAssignableFrom(possibleSuperTypeName, superParsedType);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return false;
            } catch (ClassNotFoundException e) {
                // log at debug level only since this code must not be getting used anyways, as it
                // would fail on execution since the type doesn't exist
                logger.debug("type {} not found while parsing type {}", superName, parseContext);
                return false;
            }
        }
    }
}
