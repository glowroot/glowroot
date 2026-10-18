/*
 * Copyright 2026 the original author or authors.
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.io.ByteStreams;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.objectweb.asm.Opcodes.ASM9;

public class PointcutClassFileTransformerTest {

    @Test
    public void shouldAddEnabledBooleanBridgeForConstructorOnBefore() throws Exception {
        // given
        byte[] bytes = readClassBytes(SampleConstructorOnBeforeAdvice.class);
        PointcutClassFileTransformer transformer = new PointcutClassFileTransformer();

        // when
        byte[] transformed = transformer.transform(null,
                SampleConstructorOnBeforeAdvice.class.getName().replace('.', '/'), null, null,
                bytes);

        // then
        assertThat(transformed).isNotNull();
        AtomicBoolean foundBridge = new AtomicBoolean();
        new ClassReader(transformed).accept(new ClassVisitor(ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if ("onBefore".equals(name) && descriptor.startsWith(
                        "(ZLorg/glowroot/agent/plugin/api/ThreadContext;)")) {
                    foundBridge.set(true);
                }
                return null;
            }
        }, 0);
        assertThat(foundBridge.get()).isTrue();
    }

    @Test
    public void preloadSelectionShouldMatchConstructorOnBeforeOnly() throws Exception {
        Advice onBefore = new AdviceBuilder(
                PluginDetailBuilder.buildAdviceClass(SampleConstructorOnBeforeAdvice.class))
                        .build();
        Advice onReturnOnly = new AdviceBuilder(
                PluginDetailBuilder.buildAdviceClass(SampleConstructorOnReturnAdvice.class))
                        .build();

        assertThat(AdviceCache.isConstructorOnBeforeAdvice(onBefore)).isTrue();
        assertThat(AdviceCache.isConstructorOnBeforeAdvice(onReturnOnly)).isFalse();
    }

    @Test
    public void agentModuleShouldCallPreloadBeforeRemovingPointcutTransformer() throws Exception {
        // wiring guard for AgentModule → AdviceCache.preloadConstructorOnBeforeAdviceClasses()
        Class<?> agentModule = Class.forName("org.glowroot.agent.init.AgentModule");
        String constantPool = new String(readClassBytes(agentModule), StandardCharsets.ISO_8859_1);
        assertThat(constantPool).contains("preloadConstructorOnBeforeAdviceClasses");
    }

    private static byte[] readClassBytes(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        InputStream in = clazz.getClassLoader().getResourceAsStream(resource);
        assertThat(in).isNotNull();
        try {
            return ByteStreams.toByteArray(in);
        } finally {
            in.close();
        }
    }

    @Pointcut(className = "java.lang.Object", methodName = "<init>", methodParameterTypes = {},
            nestingGroup = "pointcut-class-file-transformer-test")
    public static class SampleConstructorOnBeforeAdvice {
        @OnBefore
        public static void onBefore(@SuppressWarnings("unused") ThreadContext context) {}
    }

    @Pointcut(className = "java.lang.Object", methodName = "<init>", methodParameterTypes = {},
            nestingGroup = "pointcut-class-file-transformer-test")
    public static class SampleConstructorOnReturnAdvice {
        @OnReturn
        public static void onReturn(@SuppressWarnings("unused") ThreadContext context,
                @SuppressWarnings("unused") @BindReceiver Object receiver) {}
    }
}
