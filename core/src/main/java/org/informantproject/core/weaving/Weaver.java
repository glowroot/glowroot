/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.weaving;

import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Timer;
import org.informantproject.api.weaving.Mixin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class Weaver implements Opcodes {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    private final ImmutableList<Mixin> mixins;
    private final ImmutableList<Advice> advisors;
    @Nullable
    private final ClassLoader loader;
    private final ParsedTypeCache parsedTypeCache;

    private final WeavingMetric metric;

    Weaver(List<Mixin> mixins, List<Advice> advisors, @Nullable ClassLoader loader,
            ParsedTypeCache parsedTypeCache, WeavingMetric metric) {

        this.mixins = ImmutableList.copyOf(mixins);
        this.advisors = ImmutableList.copyOf(advisors);
        this.loader = loader;
        this.parsedTypeCache = parsedTypeCache;
        this.metric = metric;
    }

    byte[] weave(byte[] classBytes) {
        return weave$informant$metric$informant$weaving$0(classBytes, (CodeSource) null);
    }

    byte[] weave(byte[] classBytes, ProtectionDomain protectionDomain) {
        return weave$informant$metric$informant$weaving$0(classBytes,
                protectionDomain.getCodeSource());
    }

    // weird method name is following "metric marker" method naming
    private byte[] weave$informant$metric$informant$weaving$0(byte[] classBytes,
            @Nullable CodeSource codeSource) {

        Timer timer = metric.start();
        try {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            WeavingClassVisitor cv = new WeavingClassVisitor(mixins, advisors, loader,
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
                return cw.toByteArray();
            }
        } finally {
            timer.end();
        }
    }
}
