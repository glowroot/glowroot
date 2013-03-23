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

import io.informant.api.MetricTimer;
import io.informant.markers.ThreadSafe;

import java.io.PrintWriter;
import java.security.CodeSource;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class Weaver implements Opcodes {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    // this is an internal property sometimes useful for debugging errors in the weaver,
    // especially exceptions of type java.lang.VerifyError
    private static final boolean verifyWeaving =
            Boolean.valueOf(System.getProperty("informant.internal.weaving.verify"));

    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> advisors;
    @Nullable
    private final ClassLoader loader;
    private final ParsedTypeCache parsedTypeCache;

    private final WeavingMetric weavingMetric;

    Weaver(ImmutableList<MixinType> mixinTypes, ImmutableList<Advice> advisors,
            @Nullable ClassLoader loader, ParsedTypeCache parsedTypeCache,
            WeavingMetric weavingMetric) {
        this.mixinTypes = mixinTypes;
        this.advisors = advisors;
        this.loader = loader;
        this.parsedTypeCache = parsedTypeCache;
        this.weavingMetric = weavingMetric;
    }

    byte[] weave(byte[] classBytes, String className) {
        return weave$informant$metric$informant$weaving$0(classBytes, (CodeSource) null, className);
    }

    byte[] weave(byte[] classBytes, ProtectionDomain protectionDomain, String className) {
        return weave$informant$metric$informant$weaving$0(classBytes,
                protectionDomain.getCodeSource(), className);
    }

    // weird method name is following "metric marker" method naming
    private byte[] weave$informant$metric$informant$weaving$0(byte[] classBytes,
            @Nullable CodeSource codeSource, String className) {

        MetricTimer metricTimer = weavingMetric.start();
        try {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            WeavingClassVisitor cv = new WeavingClassVisitor(mixinTypes, advisors, loader,
                    parsedTypeCache, codeSource, cw);
            ClassReader cr = new ClassReader(classBytes);
            try {
                // using SKIP_FRAMES in reader and not using COMPUTE_FRAMES in writer means that
                // frames will be stripped from the bytecode which means that the jvm will fall back
                // to the old verifier which is probably(?) less penalty than using COMPUTE_FRAMES
                // see some discussion at http://mail-archive.ow2.org/asm/2008-08/msg00043.html
                cr.accept(cv, ClassReader.SKIP_FRAMES);
            } catch (ClassCircularityError e) {
                logger.error(e.getMessage(), e);
                return classBytes;
            }
            if (cv.isNothingAtAllToWeave()) {
                return classBytes;
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
    public String toString() {
        return Objects.toStringHelper(this)
                .add("mixinTypes", mixinTypes)
                .add("advisors", advisors)
                .add("loader", loader)
                .add("parsedTypeCache", parsedTypeCache)
                .toString();
    }

    private static void verifyBytecode(byte[] bytes, String className, boolean woven) {
        ClassReader verifyClassReader = new ClassReader(bytes);
        try {
            CheckClassAdapter.verify(verifyClassReader, false, new PrintWriter(System.err));
        } catch (Exception e) {
            String beforeAfter = woven ? "after" : "before";
            logger.warn("error verifying class " + beforeAfter + " weaving, " + className + ": "
                    + e.getMessage(), e);
        }
    }
}
