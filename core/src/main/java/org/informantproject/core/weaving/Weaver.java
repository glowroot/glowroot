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

import java.security.ProtectionDomain;
import java.util.List;

import org.informantproject.api.weaving.Mixin;
import org.informantproject.api.weaving.Pointcut;
import org.informantproject.core.weaving.WeavingClassVisitor.NothingToWeaveException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Weaver implements Opcodes {

    static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    private final List<Mixin> mixins;
    private final List<Advice> advisors;
    private final ParsedTypeCache parsedTypeCache;

    public Weaver(List<Mixin> mixins, List<Advice> advisors, ClassLoader loader) {
        this.mixins = mixins;
        this.advisors = advisors;
        parsedTypeCache = new ParsedTypeCache(loader);
    }

    public byte[] weave(byte[] classBytes, ProtectionDomain protectionDomain) {

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new WeavingClassVisitor(mixins, advisors, parsedTypeCache,
                protectionDomain == null ? null : protectionDomain.getCodeSource(), cw);
        ClassReader cr = new ClassReader(classBytes);
        try {
            // using SKIP_FRAMES in reader and not using COMPUTE_FRAMES in writer means that frames
            // will be stripped from the bytecode which means that the jvm will fall back to the old
            // verifier which is probably(?) less penalty than using COMPUTE_FRAMES
            // see some discussion at http://mail-archive.ow2.org/asm/2008-08/msg00043.html
            cr.accept(cv, ClassReader.SKIP_FRAMES);
            return cw.toByteArray();
        } catch (NothingToWeaveException e) {
            return classBytes;
        } catch (ClassCircularityError e) {
            logger.error(e.getMessage(), e);
            return classBytes;
        }
    }

    public static List<Advice> getAdvisors(Class<?> aspectClass) {
        List<Advice> advisors = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            if (memberClass.isAnnotationPresent(Pointcut.class)) {
                Pointcut pointcut = memberClass.getAnnotation(Pointcut.class);
                advisors.add(new Advice(pointcut, memberClass));
            }
        }
        return advisors;
    }

    public static List<Mixin> getMixins(Class<?> aspectClass) {
        List<Mixin> mixins = Lists.newArrayList();
        for (Class<?> memberClass : aspectClass.getClasses()) {
            if (memberClass.isAnnotationPresent(Mixin.class)) {
                mixins.add(memberClass.getAnnotation(Mixin.class));
            }
        }
        return mixins;
    }
}
