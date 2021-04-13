/*
 * Copyright 2015-2018 the original author or authors.
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

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.AlreadyInTransactionBehavior;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM9;

class InstrumentationSeekerClassVisitor extends ClassVisitor {

    private static final Logger logger =
            LoggerFactory.getLogger(InstrumentationSeekerClassVisitor.class);

    private final List<InstrumentationConfig> instrumentationConfigs = Lists.newArrayList();

    private @MonotonicNonNull String owner;

    InstrumentationSeekerClassVisitor() {
        super(ASM9);
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String /*@Nullable*/ [] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        this.owner = name;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
            @Nullable String signature, String /*@Nullable*/ [] exceptions) {
        return new InstrumentationAnnotationMethodVisitor(name, descriptor);
    }

    List<InstrumentationConfig> getInstrumentationConfigs() {
        return instrumentationConfigs;
    }

    private class InstrumentationAnnotationMethodVisitor extends MethodVisitor {

        private final String methodName;
        private final String descriptor;

        private @MonotonicNonNull TransactionAnnotationVisitor transactionAnnotationVisitor;
        private @MonotonicNonNull TraceEntryAnnotationVisitor traceEntryAnnotationVisitor;
        private @MonotonicNonNull TimerAnnotationVisitor timerAnnotationVisitor;

        private InstrumentationAnnotationMethodVisitor(String methodName, String descriptor) {
            super(ASM9);
            this.methodName = methodName;
            this.descriptor = descriptor;
        }

        @Override
        public @Nullable AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals("Lorg/glowroot/agent/api/Instrumentation$Transaction;")
                    || descriptor.equals("Lorg/glowroot/agent/api/Instrument$Transaction;")) {
                transactionAnnotationVisitor = new TransactionAnnotationVisitor();
                return transactionAnnotationVisitor;
            } else if (descriptor.equals("Lorg/glowroot/agent/api/Instrumentation$TraceEntry;")
                    || descriptor.equals("Lorg/glowroot/agent/api/Instrument$TraceEntry;")) {
                traceEntryAnnotationVisitor = new TraceEntryAnnotationVisitor();
                return traceEntryAnnotationVisitor;
            } else if (descriptor.equals("Lorg/glowroot/agent/api/Instrumentation$Timer;")
                    || descriptor.equals("Lorg/glowroot/agent/api/Instrument$Timer;")) {
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
                logger.error("@Instrumentation.Transaction had no transactionType attribute: {}",
                        ClassNames.fromInternalName(owner));
                return;
            }
            String transactionNameTemplate = transactionAnnotationVisitor.transactionNameTemplate;
            if (transactionNameTemplate == null) {
                logger.error(
                        "@Instrumentation.Transaction had no transactionNameTemplate attribute: {}",
                        ClassNames.fromInternalName(owner));
                return;
            }
            String traceHeadline = transactionAnnotationVisitor.traceHeadline;
            if (traceHeadline == null) {
                // supporting user code compiled against glowroot-agent-api before traceHeadline was
                // added
                traceHeadline = transactionNameTemplate;
            }
            String timerName = transactionAnnotationVisitor.timerName;
            if (timerName == null) {
                logger.error("@Instrumentation.Transaction had no timerName attribute: {}",
                        ClassNames.fromInternalName(owner));
                return;
            }
            AlreadyInTransactionBehavior alreadyInTransactionBehavior = MoreObjects.firstNonNull(
                    transactionAnnotationVisitor.alreadyInTransactionBehavior,
                    AlreadyInTransactionBehavior.CAPTURE_TRACE_ENTRY);
            instrumentationConfigs.add(startBuilder()
                    .captureKind(CaptureKind.TRANSACTION)
                    .transactionType(transactionType)
                    .transactionNameTemplate(transactionNameTemplate)
                    .traceEntryMessageTemplate(traceHeadline)
                    .timerName(timerName)
                    .alreadyInTransactionBehavior(alreadyInTransactionBehavior)
                    .build());
        }

        private void processTraceEntry() {
            if (traceEntryAnnotationVisitor == null) {
                return;
            }
            checkNotNull(owner);
            String messageTemplate = traceEntryAnnotationVisitor.messageTemplate;
            if (messageTemplate == null) {
                logger.error("@Instrumentation.TraceEntry had no messageTemplate attribute: {}",
                        ClassNames.fromInternalName(owner));
                return;
            }
            String timerName = traceEntryAnnotationVisitor.timerName;
            if (timerName == null) {
                logger.error("@Instrumentation.TraceEntry had no timerName attribute: {}",
                        ClassNames.fromInternalName(owner));
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
                logger.error("@Instrumentation.Timer had no value attribute: {}",
                        ClassNames.fromInternalName(owner));
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
            Type[] argumentTypes = Type.getArgumentTypes(descriptor);
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
        private @Nullable String traceHeadline;
        private @Nullable String timerName;
        private @Nullable AlreadyInTransactionBehavior alreadyInTransactionBehavior;

        private TransactionAnnotationVisitor() {
            super(ASM9);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (name == null) {
                return;
            }
            if (name.equals("transactionType")) {
                transactionType = (String) value;
            } else if (name.equals("transactionName")) {
                transactionNameTemplate = (String) value;
            } else if (name.equals("traceHeadline")) {
                traceHeadline = (String) value;
            } else if (name.equals("timer")) {
                timerName = (String) value;
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            if (name.equals("alreadyInTransactionBehavior")) {
                alreadyInTransactionBehavior = toProto(value);
            }
        }

        private static AlreadyInTransactionBehavior toProto(String value) {
            if (value.equals("CAPTURE_TRACE_ENTRY")) {
                return AlreadyInTransactionBehavior.CAPTURE_TRACE_ENTRY;
            } else if (value.equals("CAPTURE_NEW_TRANSACTION")) {
                return AlreadyInTransactionBehavior.CAPTURE_NEW_TRANSACTION;
            } else if (value.equals("DO_NOTHING")) {
                return AlreadyInTransactionBehavior.DO_NOTHING;
            } else {
                throw new IllegalStateException(
                        "Unexpected AlreadyInTransactionBehavior: " + value);
            }
        }
    }

    private static class TraceEntryAnnotationVisitor extends AnnotationVisitor {

        private @Nullable String messageTemplate;
        private @Nullable String timerName;

        private TraceEntryAnnotationVisitor() {
            super(ASM9);
        }

        @Override
        public void visit(@Nullable String name, Object value) {
            if (name == null) {
                return;
            }
            if (name.equals("message")) {
                messageTemplate = (String) value;
            } else if (name.equals("timer")) {
                timerName = (String) value;
            }
        }
    }

    private static class TimerAnnotationVisitor extends AnnotationVisitor {

        private @Nullable String timerName;

        private TimerAnnotationVisitor() {
            super(ASM9);
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
