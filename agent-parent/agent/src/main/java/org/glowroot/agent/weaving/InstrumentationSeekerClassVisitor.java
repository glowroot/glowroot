/*
 * Copyright 2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig.CaptureKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM5;

class InstrumentationSeekerClassVisitor extends ClassVisitor {

    private static final Logger logger =
            LoggerFactory.getLogger(InstrumentationSeekerClassVisitor.class);

    private final List<InstrumentationConfig> instrumentationConfigs = Lists.newArrayList();

    private @MonotonicNonNull String owner;

    InstrumentationSeekerClassVisitor() {
        super(ASM5);
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String /*@Nullable*/[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        this.owner = name;
    }

    @Override
    @Nullable
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions) {
        return new InstrumentAnnotationMethodVisitor(name, desc);
    }

    List<InstrumentationConfig> getInstrumentationConfigs() {
        return instrumentationConfigs;
    }

    private class InstrumentAnnotationMethodVisitor extends MethodVisitor {

        private final String methodName;
        private final String desc;

        private @MonotonicNonNull TransactionAnnotationVisitor transactionAnnotationVisitor;
        private @MonotonicNonNull TraceEntryAnnotationVisitor traceEntryAnnotationVisitor;
        private @MonotonicNonNull TimerAnnotationVisitor timerAnnotationVisitor;

        private InstrumentAnnotationMethodVisitor(String methodName, String desc) {
            super(ASM5);
            this.methodName = methodName;
            this.desc = desc;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (desc.equals("Lorg/glowroot/agent/api/Instrument$Transaction;")) {
                transactionAnnotationVisitor = new TransactionAnnotationVisitor();
                return transactionAnnotationVisitor;
            } else if (desc.equals("Lorg/glowroot/agent/api/Instrument$TraceEntry;")) {
                traceEntryAnnotationVisitor = new TraceEntryAnnotationVisitor();
                return traceEntryAnnotationVisitor;
            } else if (desc.equals("Lorg/glowroot/agent/api/Instrument$Timer;")) {
                timerAnnotationVisitor = new TimerAnnotationVisitor();
                return timerAnnotationVisitor;
            }
            return null;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            processTransaction();
            processTraceEntry();
            processTimer();
        }

        private void processTransaction() {
            if (transactionAnnotationVisitor == null) {
                return;
            }
            checkNotNull(owner);
            String transactionType = transactionAnnotationVisitor.transactionType;
            if (transactionType == null) {
                logger.error("@Instrument.Transaction had no transactionType attribute: {}",
                        Type.getType(owner).getClassName());
                return;
            }
            String transactionNameTemplate =
                    transactionAnnotationVisitor.transactionNameTemplate;
            if (transactionNameTemplate == null) {
                logger.error("@Instrument.Transaction had no transactionNameTemplate attribute: {}",
                        Type.getType(owner).getClassName());
                return;
            }
            String timerName = transactionAnnotationVisitor.timerName;
            if (timerName == null) {
                logger.error("@Instrument.Transaction had no timerName attribute: {}",
                        Type.getType(owner).getClassName());
                return;
            }
            instrumentationConfigs.add(startBuilder()
                    .captureKind(CaptureKind.TRANSACTION)
                    .transactionType(transactionType)
                    .transactionNameTemplate(transactionNameTemplate)
                    .timerName(timerName)
                    .build());
        }

        private void processTraceEntry() {
            if (traceEntryAnnotationVisitor == null) {
                return;
            }
            checkNotNull(owner);
            String messageTemplate = traceEntryAnnotationVisitor.messageTemplate;
            if (messageTemplate == null) {
                logger.error("@Instrument.TraceEntry had no messageTemplate attribute: {}",
                        Type.getType(owner).getClassName());
                return;
            }
            String timerName = traceEntryAnnotationVisitor.timerName;
            if (timerName == null) {
                logger.error("@Instrument.TraceEntry had no timerName attribute: {}",
                        Type.getType(owner).getClassName());
                return;
            }
            instrumentationConfigs.add(startBuilder()
                    .captureKind(CaptureKind.TRACE_ENTRY)
                    .traceEntryMessageTemplate(messageTemplate)
                    .timerName(timerName)
                    .build());
        }

        private void processTimer() {
            if (timerAnnotationVisitor == null) {
                return;
            }
            checkNotNull(owner);
            String timerName = timerAnnotationVisitor.timerName;
            if (timerName == null) {
                logger.error("@Instrument.Timer had no value attribute: {}",
                        Type.getType(owner).getClassName());
                return;
            }
            instrumentationConfigs.add(startBuilder()
                    .captureKind(CaptureKind.TIMER)
                    .timerName(timerName)
                    .build());
        }

        @RequiresNonNull("owner")
        private ImmutableInstrumentationConfig.Builder startBuilder() {
            Type type = Type.getObjectType(owner);
            Type[] argumentTypes = Type.getArgumentTypes(desc);
            ImmutableInstrumentationConfig.Builder builder =
                    ImmutableInstrumentationConfig.builder()
                            .className(type.getClassName())
                            .methodName(methodName);
            for (Type argumentType : argumentTypes) {
                builder.addMethodParameterTypes(argumentType.getClassName());
            }
            return builder;
        }
    }

    private static class TransactionAnnotationVisitor extends AnnotationVisitor {

        private @Nullable String transactionType;
        private @Nullable String transactionNameTemplate;
        private @Nullable String timerName;

        private TransactionAnnotationVisitor() {
            super(ASM5);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (name == null) {
                return;
            }
            if (name.equals("transactionType")) {
                transactionType = (String) value;
            } else if (name.equals("transactionNameTemplate")) {
                transactionNameTemplate = (String) value;
            } else if (name.equals("timerName")) {
                timerName = (String) value;
            }
        }
    }

    private static class TraceEntryAnnotationVisitor extends AnnotationVisitor {

        private @Nullable String messageTemplate;
        private @Nullable String timerName;

        private TraceEntryAnnotationVisitor() {
            super(ASM5);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (name == null) {
                return;
            }
            if (name.equals("messageTemplate")) {
                messageTemplate = (String) value;
            } else if (name.equals("timerName")) {
                timerName = (String) value;
            }
        }
    }

    private static class TimerAnnotationVisitor extends AnnotationVisitor {

        private @Nullable String timerName;

        private TimerAnnotationVisitor() {
            super(ASM5);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (name == null) {
                return;
            }
            if (name.equals("value")) {
                timerName = (String) value;
            }
        }
    }
}
