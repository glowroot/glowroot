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

import java.util.List;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import org.glowroot.agent.bytecode.api.ThreadContextThreadLocal;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.TimerNameCache;
import org.glowroot.agent.impl.TransactionRegistry;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_8;

// #1115: Helidon MP (Weld 5 / jakarta) needs the same stripGlowrootTypes hook as javax Weld
public class WeldHackTest {

    @Test
    public void shouldStripGlowrootTypesForJavaxCheckDelegateType() throws Exception {
        assertStripInjected("(Ljavax/enterprise/inject/spi/Decorator;)V",
                "javax/enterprise/inject/spi/Decorator");
    }

    @Test
    public void shouldStripGlowrootTypesForJakartaCheckDelegateType() throws Exception {
        assertStripInjected("(Ljakarta/enterprise/inject/spi/Decorator;)V",
                "jakarta/enterprise/inject/spi/Decorator");
    }

    private static void assertStripInjected(String checkDelegateDescriptor,
            String decoratorInternalName) throws Exception {
        byte[] original = fakeDecoratorsClass(checkDelegateDescriptor, decoratorInternalName);
        Weaver weaver = newEmptyWeaver();
        byte[] woven = weaver.weave(original, ImportantClassNames.JBOSS_WELD_HACK_CLASS_NAME, null,
                null, null);
        assertThat(woven).isNotNull();
        assertThat(new String(woven, ISO_8859_1)).contains("stripGlowrootTypes");
    }

    private static byte[] fakeDecoratorsClass(String checkDelegateDescriptor,
            String decoratorInternalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, ImportantClassNames.JBOSS_WELD_HACK_CLASS_NAME, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "checkDelegateType",
                checkDelegateDescriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, decoratorInternalName, "getDecoratedTypes",
                "()Ljava/util/Set;", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Weaver newEmptyWeaver() {
        Supplier<List<Advice>> advisorsSupplier =
                Suppliers.<List<Advice>>ofInstance(ImmutableList.<Advice>of());
        AnalyzedWorld analyzedWorld = new AnalyzedWorld(advisorsSupplier,
                ImmutableList.<ShimType>of(), ImmutableList.<MixinType>of(), null);
        TransactionRegistry transactionRegistry = mock(TransactionRegistry.class);
        when(transactionRegistry.getCurrentThreadContextHolder())
                .thenReturn(new ThreadContextThreadLocal().getHolder());
        return new Weaver(advisorsSupplier, ImmutableList.<ShimType>of(),
                ImmutableList.<MixinType>of(), analyzedWorld, transactionRegistry,
                Ticker.systemTicker(), new TimerNameCache(), mock(ConfigService.class));
    }
}
