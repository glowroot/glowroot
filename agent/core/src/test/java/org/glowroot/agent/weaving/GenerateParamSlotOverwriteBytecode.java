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

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.F_FULL;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_6;

/**
 * Repro shape for #721: method parameter local slot is overwritten with a different reference
 * type inside a try block that has an exception handler (ServletContextListener-style).
 */
public class GenerateParamSlotOverwriteBytecode {

    static LazyDefinedClass generate() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        MethodVisitor mv;

        String className = "ParamSlotOverwrite";
        String iface = Test.class.getName().replace('.', '/');

        cw.visit(V1_6, ACC_PUBLIC + ACC_SUPER, className, null, "java/lang/Object",
                new String[] {iface});

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            // void contextInitialized(Runnable event)
            // try {
            //   // overwrite slot 1 (event: Runnable) with String (from event.toString())
            //   event = event.toString(); // as bytecode: toString + astore_1
            //   ((String) event).length();
            // } catch (Throwable t) { throw t; }
            mv = cw.visitMethod(ACC_PUBLIC, "contextInitialized", "(Ljava/lang/Runnable;)V", null,
                    null);
            mv.visitCode();
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchHandler = new Label();
            Label exit = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");

            mv.visitLabel(tryStart);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString",
                    "()Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, 1);
            // frame after overwrite: locals this, String
            mv.visitFrame(F_FULL, 2, new Object[] {className, "java/lang/String"}, 0, null);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitTypeInsn(CHECKCAST, "java/lang/String");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
            mv.visitInsn(POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(GOTO, exit);

            mv.visitLabel(catchHandler);
            // handler frame must see String in slot 1 (updated type), not Runnable
            mv.visitFrame(F_FULL, 2, new Object[] {className, "java/lang/String"}, 1,
                    new Object[] {"java/lang/Throwable"});
            mv.visitInsn(ATHROW);

            mv.visitLabel(exit);
            mv.visitFrame(F_FULL, 2, new Object[] {className, "java/lang/String"}, 0, null);
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }
        cw.visitEnd();

        return ImmutableLazyDefinedClass.builder()
                .type(Type.getObjectType(className))
                .bytes(cw.toByteArray())
                .build();
    }

    public interface Test {
        void contextInitialized(Runnable event);
    }

    @Pointcut(className = "ParamSlotOverwrite", methodName = "contextInitialized",
            methodParameterTypes = {"java.lang.Runnable"}, timerName = "param-slot-overwrite")
    public static class ParamSlotOverwriteAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return true;
        }
        @OnBefore
        public static void onBefore() {
            SomeAspectThreadLocals.onBeforeCount.increment();
        }
        @OnReturn
        public static void onReturn() {
            SomeAspectThreadLocals.onReturnCount.increment();
        }
        @OnThrow
        public static void onThrow() {
            SomeAspectThreadLocals.onThrowCount.increment();
        }
        @OnAfter
        public static void onAfter() {
            SomeAspectThreadLocals.onAfterCount.increment();
        }
    }
}
