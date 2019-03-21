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

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.plugin.api.weaving.MethodModifier;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.weaving.PluginDetail.MixinClass;
import org.glowroot.agent.weaving.PluginDetail.PointcutClass;
import org.glowroot.agent.weaving.PluginDetail.PointcutMethod;
import org.glowroot.common.util.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM7;

class PluginDetailBuilder {

    private static final Logger logger = LoggerFactory.getLogger(PluginDetailBuilder.class);

    private PluginDescriptor pluginDescriptor;

    PluginDetailBuilder(PluginDescriptor pluginDescriptor) {
        this.pluginDescriptor = pluginDescriptor;
    }

    PluginDetail build() throws IOException {
        ImmutablePluginDetail.Builder builder = ImmutablePluginDetail.builder();
        for (String aspect : pluginDescriptor.aspects()) {
            byte[] bytes =
                    getBytes(ClassNames.toInternalName(aspect), pluginDescriptor.pluginJar());
            AspectClassVisitor cv = new AspectClassVisitor();
            new ClassReader(bytes).accept(cv, ClassReader.SKIP_CODE);
            for (String innerClassName : cv.innerClassNames) {
                bytes = getBytes(innerClassName, pluginDescriptor.pluginJar());
                MemberClassVisitor mcv = new MemberClassVisitor();
                new ClassReader(bytes).accept(mcv, ClassReader.SKIP_CODE);
                if (mcv.pointcutAnnotationVisitor != null) {
                    builder.addPointcutClasses(mcv.buildPointcutClass(bytes,
                            pluginDescriptor.collocate(), pluginDescriptor.pluginJar()));
                } else if (mcv.mixinAnnotationVisitor != null) {
                    builder.addMixinClasses(
                            mcv.buildMixinClass(pluginDescriptor.collocate(), bytes));
                } else if (mcv.shim) {
                    builder.addShimClasses(mcv.buildShimClass(pluginDescriptor.collocate()));
                }
            }
        }
        return builder.build();
    }

    static PointcutClass buildAdviceClass(byte[] bytes) {
        MemberClassVisitor acv = new MemberClassVisitor();
        new ClassReader(bytes).accept(acv, ClassReader.SKIP_CODE);
        return acv.buildPointcutClass(bytes, false, null);
    }

    static byte[] getBytes(String className, @Nullable File pluginJar) throws IOException {
        String resourceName = "/" + className + ".class";
        URL url = PluginDetailBuilder.class.getResource(resourceName);
        if (url != null) {
            return Resources.toByteArray(url);
        }
        if (pluginJar != null) {
            url = new URL("jar:" + pluginJar.toURI() + "!" + resourceName);
            return Resources.toByteArray(url);
        }
        throw new IOException("Class not found: " + className);
    }

    @OnlyUsedByTests
    static PointcutClass buildAdviceClass(Class<?> clazz) throws IOException {
        return buildAdviceClassLookAtSuperClass(ClassNames.toInternalName(clazz.getName()));
    }

    @OnlyUsedByTests
    static MixinClass buildMixinClass(Class<?> clazz) throws IOException {
        URL url = checkNotNull(PluginDetailBuilder.class
                .getResource("/" + ClassNames.toInternalName(clazz.getName()) + ".class"));
        byte[] bytes = Resources.asByteSource(url).read();
        MemberClassVisitor mcv = new MemberClassVisitor();
        new ClassReader(bytes).accept(mcv, ClassReader.SKIP_CODE);
        return mcv.buildMixinClass(false, bytes);
    }

    @OnlyUsedByTests
    private static PointcutClass buildAdviceClassLookAtSuperClass(String internalName)
            throws IOException {
        URL url =
                checkNotNull(PluginDetailBuilder.class.getResource("/" + internalName + ".class"));
        byte[] bytes = Resources.asByteSource(url).read();
        MemberClassVisitor mcv = new MemberClassVisitor();
        new ClassReader(bytes).accept(mcv, ClassReader.SKIP_CODE);
        ImmutablePointcutClass pointcutClass = mcv.buildPointcutClass(bytes, false, null);
        String superName = checkNotNull(mcv.superName);
        if (!"java/lang/Object".equals(superName)) {
            pointcutClass = ImmutablePointcutClass.builder()
                    .copyFrom(pointcutClass)
                    .addAllMethods(buildAdviceClassLookAtSuperClass(superName).methods())
                    .build();
        }
        return pointcutClass;
    }

    private static class AspectClassVisitor extends ClassVisitor {

        private List<String> innerClassNames = Lists.newArrayList();

        private AspectClassVisitor() {
            super(ASM7);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            innerClassNames.add(name);
        }
    }

    private static class MemberClassVisitor extends ClassVisitor {

        private @Nullable String name;
        private @Nullable String superName;
        private String /*@Nullable*/ [] interfaces;
        private @Nullable PointcutAnnotationVisitor pointcutAnnotationVisitor;
        private @Nullable MixinAnnotationVisitor mixinAnnotationVisitor;
        private List<PointcutMethodVisitor> pointcutMethodVisitors = Lists.newArrayList();
        private List<MixinMethodVisitor> mixinMethodVisitors = Lists.newArrayList();
        private boolean shim;

        private MemberClassVisitor() {
            super(ASM7);
        }

        @Override
        public void visit(int version, int access, String name, @Nullable String signature,
                @Nullable String superName, String /*@Nullable*/ [] interfaces) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals("Lorg/glowroot/agent/plugin/api/weaving/Pointcut;")) {
                pointcutAnnotationVisitor = new PointcutAnnotationVisitor();
                return pointcutAnnotationVisitor;
            }
            if (descriptor.equals("Lorg/glowroot/agent/plugin/api/weaving/Mixin;")) {
                mixinAnnotationVisitor = new MixinAnnotationVisitor();
                return mixinAnnotationVisitor;
            }
            if (descriptor.equals("Lorg/glowroot/agent/plugin/api/weaving/Shim;")) {
                shim = true;
            }
            return null;
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String descriptor,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            if (pointcutAnnotationVisitor != null) {
                PointcutMethodVisitor methodVisitor = new PointcutMethodVisitor(name, descriptor);
                pointcutMethodVisitors.add(methodVisitor);
                return methodVisitor;
            }
            if (mixinAnnotationVisitor != null) {
                MixinMethodVisitor methodVisitor = new MixinMethodVisitor(name, descriptor);
                mixinMethodVisitors.add(methodVisitor);
                return methodVisitor;
            }
            return null;
        }

        private ImmutablePointcutClass buildPointcutClass(byte[] bytes,
                boolean collocateInClassLoader, @Nullable File pluginJar) {
            ImmutablePointcutClass.Builder builder = ImmutablePointcutClass.builder()
                    .type(Type.getObjectType(checkNotNull(name)));
            for (PointcutMethodVisitor methodVisitor : pointcutMethodVisitors) {
                builder.addMethods(methodVisitor.build());
            }
            return builder.pointcut(checkNotNull(pointcutAnnotationVisitor).build())
                    .bytes(bytes)
                    .collocateInClassLoader(collocateInClassLoader)
                    .pluginJar(pluginJar)
                    .build();
        }

        private ImmutableMixinClass buildMixinClass(boolean collocateInClassLoader, byte[] bytes) {
            String initMethodName = null;
            for (MixinMethodVisitor methodVisitor : mixinMethodVisitors) {
                if (methodVisitor.init) {
                    if (initMethodName != null) {
                        throw new IllegalStateException("@Mixin has more than one @MixinInit");
                    }
                    initMethodName = methodVisitor.name;
                }
            }
            ImmutableMixinClass.Builder builder = ImmutableMixinClass.builder()
                    .type(Type.getObjectType(checkNotNull(name)));
            if (interfaces != null) {
                for (String iface : interfaces) {
                    if (collocateInClassLoader
                            && !iface.endsWith(PluginClassRenamer.MIXIN_SUFFIX)) {
                        // see PluginClassRenamer.hack() for reason why consistent Mixin suffix is
                        // important
                        logger.warn("mixin interface name should end with \"Mixin\": {}", iface);
                    }
                    builder.addInterfaces(Type.getObjectType(iface));
                }
            }
            return builder.mixin(checkNotNull(mixinAnnotationVisitor).build())
                    .initMethodName(initMethodName)
                    .bytes(bytes)
                    .build();
        }

        private ImmutableShimClass buildShimClass(boolean collocateInClassLoader) {
            if (collocateInClassLoader
                    && !checkNotNull(name).endsWith(PluginClassRenamer.SHIM_SUFFIX)) {
                // see PluginClassRenamer.hack() for reason why consistent Shim suffix is important
                logger.warn("shim interface name should end with \"Shim\": {}", name);
            }
            return ImmutableShimClass.builder()
                    .type(Type.getObjectType(checkNotNull(name)))
                    .build();
        }
    }

    private static class PointcutAnnotationVisitor extends AnnotationVisitor {

        private String className = "";
        private String classAnnotation = "";
        private String subTypeRestriction = "";
        private String superTypeRestriction = "";
        private String methodName = "";
        private String methodAnnotation = "";
        private List<String> methodParameterTypes = Lists.newArrayList();
        private String methodReturnType = "";
        private List<MethodModifier> methodModifiers = Lists.newArrayList();
        private String nestingGroup = "";
        private String timerName = "";
        private int order;
        private String suppressibleUsingKey = "";
        private String suppressionKey = "";

        private PointcutAnnotationVisitor() {
            super(ASM7);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if ("className".equals(name)) {
                className = (String) value;
            } else if ("classAnnotation".equals(name)) {
                classAnnotation = (String) value;
            } else if ("subTypeRestriction".equals(name)) {
                subTypeRestriction = (String) value;
            } else if ("superTypeRestriction".equals(name)) {
                superTypeRestriction = (String) value;
            } else if ("methodName".equals(name)) {
                methodName = (String) value;
            } else if ("methodAnnotation".equals(name)) {
                methodAnnotation = (String) value;
            } else if ("methodReturnType".equals(name)) {
                methodReturnType = (String) value;
            } else if ("nestingGroup".equals(name)) {
                nestingGroup = (String) value;
            } else if ("timerName".equals(name)) {
                timerName = (String) value;
            } else if ("order".equals(name)) {
                order = (Integer) value;
            } else if ("suppressibleUsingKey".equals(name)) {
                suppressibleUsingKey = (String) value;
            } else if ("suppressionKey".equals(name)) {
                suppressionKey = (String) value;
            } else {
                throw new IllegalStateException("Unexpected @Pointcut attribute name: " + name);
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("methodParameterTypes".equals(name)) {
                return new StringArrayAnnotationVisitor(methodParameterTypes);
            } else if ("methodModifiers".equals(name)) {
                return new MethodModifierArrayAnnotationVisitor(methodModifiers);
            } else {
                throw new IllegalStateException("Unexpected @Pointcut attribute name: " + name);
            }
        }

        private Pointcut build() {
            return new Pointcut() {
                @Override
                public Class<? extends Annotation> annotationType() {
                    return Pointcut.class;
                }
                @Override
                public String className() {
                    return className;
                }
                @Override
                public String classAnnotation() {
                    return classAnnotation;
                }
                @Override
                public String subTypeRestriction() {
                    return subTypeRestriction;
                }
                @Override
                public String superTypeRestriction() {
                    return superTypeRestriction;
                }
                @Override
                public String methodName() {
                    return methodName;
                }
                @Override
                public String methodAnnotation() {
                    return methodAnnotation;
                }
                @Override
                public String[] methodParameterTypes() {
                    return Iterables.toArray(methodParameterTypes, String.class);
                }
                @Override
                public String methodReturnType() {
                    return methodReturnType;
                }
                @Override
                public MethodModifier[] methodModifiers() {
                    return Iterables.toArray(methodModifiers, MethodModifier.class);
                }
                @Override
                public String nestingGroup() {
                    return nestingGroup;
                }
                @Override
                public String timerName() {
                    return timerName;
                }
                @Override
                public int order() {
                    return order;
                }
                @Override
                public String suppressibleUsingKey() {
                    return suppressibleUsingKey;
                }
                @Override
                public String suppressionKey() {
                    return suppressionKey;
                }
            };
        }
    }

    private static class PointcutMethodVisitor extends MethodVisitor {

        private final String name;
        private final String descriptor;
        private final List<Type> annotationTypes = Lists.newArrayList();
        private final Map<Integer, List<Type>> parameterAnnotationTypes = Maps.newHashMap();

        private PointcutMethodVisitor(String name, String descriptor) {
            super(ASM7);
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotationTypes.add(Type.getType(descriptor));
            return null;
        }

        @Override
        public @Nullable AnnotationVisitor visitParameterAnnotation(int parameter,
                String descriptor, boolean visible) {
            List<Type> list = parameterAnnotationTypes.get(parameter);
            if (list == null) {
                list = Lists.newArrayList();
                parameterAnnotationTypes.put(parameter, list);
            }
            list.add(Type.getType(descriptor));
            return null;
        }

        private PointcutMethod build() {
            return ImmutablePointcutMethod.builder()
                    .name(name)
                    .descriptor(descriptor)
                    .addAllAnnotationTypes(annotationTypes)
                    .putAllParameterAnnotationTypes(parameterAnnotationTypes)
                    .build();
        }
    }

    private static class MixinAnnotationVisitor extends AnnotationVisitor {

        private MixinAnnotationVisitor() {
            super(ASM7);
        }

        private List<String> values = Lists.newArrayList();

        @Override
        public void visit(@Nullable String name, Object value) {
            throw new IllegalStateException("Unexpected @Mixin attribute name: " + name);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("value".equals(name)) {
                return new StringArrayAnnotationVisitor(values);
            } else {
                throw new IllegalStateException("Unexpected @Mixin attribute name: " + name);
            }
        }

        private Mixin build() {
            return new Mixin() {
                @Override
                public Class<? extends Annotation> annotationType() {
                    return Mixin.class;
                }
                @Override
                public String[] value() {
                    return Iterables.toArray(values, String.class);
                }
            };
        }
    }

    private static class MixinMethodVisitor extends MethodVisitor {

        private final String name;
        private final String descriptor;

        private boolean init;

        private MixinMethodVisitor(String name, String descriptor) {
            super(ASM7);
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals("Lorg/glowroot/agent/plugin/api/weaving/MixinInit;")) {
                if (Type.getArgumentTypes(this.descriptor).length > 0) {
                    throw new IllegalStateException("@MixinInit method cannot have any parameters");
                }
                if (!Type.getReturnType(this.descriptor).equals(Type.VOID_TYPE)) {
                    throw new IllegalStateException("@MixinInit method must return void");
                }
                init = true;
            }
            return null;
        }
    }

    private static class StringArrayAnnotationVisitor extends AnnotationVisitor {

        private final List<String> list;

        private StringArrayAnnotationVisitor(List<String> list) {
            super(ASM7);
            this.list = list;
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            list.add((String) value);
        }
    }

    private static class MethodModifierArrayAnnotationVisitor extends AnnotationVisitor {

        private final List<MethodModifier> list;

        private MethodModifierArrayAnnotationVisitor(List<MethodModifier> list) {
            super(ASM7);
            this.list = list;
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            list.add(MethodModifier.valueOf(value));
        }
    }
}
