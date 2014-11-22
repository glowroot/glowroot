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

import java.io.PrintWriter;
import java.security.CodeSource;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.weaving.WeavingClassVisitor.PointcutClassFoundException;
import org.glowroot.weaving.WeavingClassVisitor.ShortCircuitException;
import org.glowroot.weaving.WeavingTimerService.WeavingTimer;

class Weaver {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    // this is an internal property sometimes useful for debugging errors in the weaver,
    // especially exceptions of type java.lang.VerifyError
    private static final boolean verifyWeaving =
            Boolean.getBoolean("glowroot.internal.weaving.verify");

    private final Supplier<List<Advice>> advisors;
    private final ImmutableList<MixinType> mixinTypes;
    private final AnalyzedWorld analyzedWorld;
    private final WeavingTimerService weavingTimerService;
    private final boolean metricWrapperMethods;

    Weaver(Supplier<List<Advice>> advisors, List<MixinType> mixinTypes,
            AnalyzedWorld analyzedWorld, WeavingTimerService weavingTimerService,
            boolean metricWrapperMethods) {
        this.advisors = advisors;
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.analyzedWorld = analyzedWorld;
        this.weavingTimerService = weavingTimerService;
        this.metricWrapperMethods = metricWrapperMethods;
    }

    byte/*@Nullable*/[] weave(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        if (metricWrapperMethods) {
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
        WeavingTimer weavingTimer = weavingTimerService.start();
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
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            WeavingClassVisitor cv = new WeavingClassVisitor(cw, advisors.get(), mixinTypes,
                    loader, analyzedWorld, codeSource, metricWrapperMethods);
            ClassReader cr = new ClassReader(classBytes);
            boolean shortCircuitException = false;
            boolean pointcutClassFoundException = false;
            try {
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
            } catch (ShortCircuitException e) {
                shortCircuitException = true;
            } catch (PointcutClassFoundException e) {
                pointcutClassFoundException = true;
            } catch (ClassCircularityError e) {
                logger.error(e.getMessage(), e);
                return null;
            }
            if (shortCircuitException || cv.isInterfaceSoNothingToWeave()) {
                return null;
            } else if (pointcutClassFoundException) {
                ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                PointcutClassVisitor cv2 = new PointcutClassVisitor(cw2);
                ClassReader cr2 = new ClassReader(classBytes);
                cr2.accept(cv2, ClassReader.EXPAND_FRAMES);
                return cw2.toByteArray();
            } else {
                byte[] wovenBytes = cw.toByteArray();
                if (verifyWeaving) {
                    verifyBytecode(classBytes, className, loader, false);
                    verifyBytecode(wovenBytes, className, loader, true);
                }
                return wovenBytes;
            }
        } finally {
            weavingTimer.stop();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("advisors", advisors)
                .add("mixinTypes", mixinTypes)
                .add("analyzedWorld", analyzedWorld)
                .add("weavingTimerService", weavingTimerService)
                .add("metricWrapperMethods", metricWrapperMethods)
                .toString();
    }

    private static void verifyBytecode(byte[] bytes, String className,
            @Nullable ClassLoader loader, boolean woven) {
        ClassReader verifyClassReader = new ClassReader(bytes);
        try {
            CheckClassAdapter.verify(verifyClassReader, loader, false, new PrintWriter(System.err));
        } catch (Throwable t) {
            if (woven) {
                logger.warn("error verifying class {} (after weaving): {}", className,
                        t.getMessage(), t);
            } else {
                logger.warn("error verifying class {} (before weaving): {}", className,
                        t.getMessage(), t);
            }
        }
    }
}
