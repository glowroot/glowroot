/*
 * Copyright 2018-2019 the original author or authors.
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

import java.io.IOException;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.weaving.Advice.AdviceParameter;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.agent.weaving.PluginDetail.PointcutClass;

class PluginClassRenamer {

    static final String SHIM_SUFFIX = "Shim";
    static final String MIXIN_SUFFIX = "Mixin";

    private static final Logger logger = LoggerFactory.getLogger(PluginClassRenamer.class);

    private final PointcutClass adviceClass;
    private final String rootPackageName;
    private final String bootstrapSafePackageName;

    private final Set<String> processed = Sets.newHashSet();

    PluginClassRenamer(PointcutClass adviceClass) {
        this.adviceClass = adviceClass;
        String internalName = adviceClass.type().getInternalName();
        int index = internalName.lastIndexOf('/');
        if (index == -1) {
            rootPackageName = "";
        } else {
            rootPackageName = internalName.substring(0, index);
        }
        bootstrapSafePackageName = rootPackageName + "/_/";
    }

    @Nullable
    Advice buildNonBootstrapLoaderAdvice(Advice advice) {
        if (rootPackageName.isEmpty()) {
            logger.warn("advice needs to be in a named package in order to collocate the advice in"
                    + " the class loader that it is used from (as opposed to located in the"
                    + " bootstrap class loader)");
            return null;
        } else {
            return ImmutableAdvice.builder()
                    .copyFrom(advice)
                    .adviceType(hack(advice.adviceType()))
                    .travelerType(hack(advice.travelerType()))
                    .isEnabledAdvice(hack(advice.isEnabledAdvice()))
                    .onBeforeAdvice(hack(advice.onBeforeAdvice()))
                    .onReturnAdvice(hack(advice.onReturnAdvice()))
                    .onThrowAdvice(hack(advice.onThrowAdvice()))
                    .onAfterAdvice(hack(advice.onAfterAdvice()))
                    .isEnabledParameters(hack(advice.isEnabledParameters()))
                    .onBeforeParameters(hack(advice.onBeforeParameters()))
                    .onReturnParameters(hack(advice.onReturnParameters()))
                    .onThrowParameters(hack(advice.onThrowParameters()))
                    .onAfterParameters(hack(advice.onAfterParameters()))
                    .build();
        }
    }

    @Nullable
    LazyDefinedClass buildNonBootstrapLoaderAdviceClass() throws IOException {
        if (rootPackageName.isEmpty()) {
            logger.warn("advice needs to be in a named package in order to co-locate the advice in"
                    + " the class loader that it is used from (as opposed to located in the"
                    + " bootstrap class loader)");
            return null;
        } else {
            return build(adviceClass.type().getInternalName(), adviceClass.bytes());
        }
    }

    private LazyDefinedClass build(String internalName, byte[] origBytes) throws IOException {
        processed.add(internalName);
        PluginClassRemapper remapper = new PluginClassRemapper();
        ImmutableLazyDefinedClass.Builder builder = ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType(remapper.mapType(internalName)));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassRemapper(cw, remapper);
        ClassReader cr = new ClassReader(origBytes);
        cr.accept(cv, 0);
        builder.bytes(cw.toByteArray());
        for (String unprocessed : remapper.unprocessed) {
            builder.addDependencies(build(unprocessed));
        }
        return builder.build();
    }

    private LazyDefinedClass build(String internalName) throws IOException {
        return build(internalName,
                PluginDetailBuilder.getBytes(internalName, adviceClass.pluginJar()));
    }

    private @Nullable Method hack(@Nullable Method method) {
        if (method == null) {
            return null;
        }
        Type[] argumentTypes = method.getArgumentTypes();
        Type[] hackedArgumentTypes = new Type[argumentTypes.length];
        for (int i = 0; i < argumentTypes.length; i++) {
            hackedArgumentTypes[i] = hack(argumentTypes[i]);
        }
        return new Method(method.getName(), hack(method.getReturnType()), hackedArgumentTypes);
    }

    private List<AdviceParameter> hack(List<AdviceParameter> parameters) {
        List<AdviceParameter> hackedParameters = Lists.newArrayList();
        for (AdviceParameter parameter : parameters) {
            hackedParameters.add(ImmutableAdviceParameter.builder()
                    .copyFrom(parameter)
                    .type(hack(parameter.type()))
                    .build());
        }
        return hackedParameters;
    }

    private @PolyNull Type hack(@PolyNull Type type) {
        if (type == null) {
            return null;
        }
        String internalName = type.getInternalName();
        if (collocate(internalName)) {
            return Type.getObjectType(internalName + "_");
        } else {
            return type;
        }
    }

    private boolean collocate(String internalName) {
        return internalName.startsWith(rootPackageName) && !internalName.endsWith(MIXIN_SUFFIX)
                && !internalName.endsWith(SHIM_SUFFIX)
                && !internalName.startsWith(bootstrapSafePackageName);
    }

    private class PluginClassRemapper extends Remapper {

        private final Set<String> unprocessed = Sets.newHashSet();

        @Override
        public String map(String internalName) {
            if (collocate(internalName)) {
                if (!processed.contains(internalName)) {
                    unprocessed.add(internalName);
                }
                return internalName + "_";
            } else {
                return internalName;
            }
        }
    }
}
