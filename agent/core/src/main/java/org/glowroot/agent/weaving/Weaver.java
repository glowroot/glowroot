/*
 * Copyright 2012-2018 the original author or authors.
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.security.CodeSource;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Supplier;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.primitives.Longs;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.impl.ThreadContextImpl;
import org.glowroot.agent.impl.TimerImpl;
import org.glowroot.agent.impl.TimerNameCache;
import org.glowroot.agent.impl.TransactionRegistry;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.util.IterableWithSelfRemovableEntries;
import org.glowroot.agent.util.IterableWithSelfRemovableEntries.SelfRemovableEntry;
import org.glowroot.common.util.ScheduledRunnable.TerminateSubsequentExecutionsException;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.objectweb.asm.Opcodes.ASM6;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

public class Weaver {

    public static final String JBOSS_WELD_HACK_CLASS_NAME = "org/jboss/weld/util/Decorators";
    public static final String JBOSS_MODULES_HACK_CLASS_NAME = "org/jboss/modules/Module";
    public static final String FELIX_OSGI_HACK_CLASS_NAME =
            "org/apache/felix/framework/BundleWiringImpl";
    public static final String FELIX3_OSGI_HACK_CLASS_NAME =
            "org/apache/felix/framework/ModuleImpl";
    public static final String ECLIPSE_OSGI_HACK_CLASS_NAME =
            "org/eclipse/osgi/internal/framework/EquinoxContainer";
    public static final String JBOSS4_HACK_CLASS_NAME = "org/jboss/system/server/ServerImpl";

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    // useful for debugging java.lang.VerifyErrors
    private static final @Nullable String DEBUG_CLASS_NAME =
            System.getProperty("glowroot.weaving.debugClassName");

    private final Supplier<List<Advice>> advisors;
    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;
    private final AnalyzedWorld analyzedWorld;
    private final TransactionRegistry transactionRegistry;
    private final Ticker ticker;
    private final TimerName timerName;

    private volatile boolean weavingTimerEnabled;

    private volatile boolean weavingDisabledForLoggingDeadlock;

    private final IterableWithSelfRemovableEntries<ActiveWeaving> activeWeavings =
            new IterableWithSelfRemovableEntries<ActiveWeaving>();

    public Weaver(Supplier<List<Advice>> advisors, List<ShimType> shimTypes,
            List<MixinType> mixinTypes, AnalyzedWorld analyzedWorld,
            TransactionRegistry transactionRegistry, Ticker ticker, TimerNameCache timerNameCache,
            final ConfigService configService) {
        this.advisors = advisors;
        this.shimTypes = ImmutableList.copyOf(shimTypes);
        this.mixinTypes = ImmutableList.copyOf(mixinTypes);
        this.analyzedWorld = analyzedWorld;
        this.transactionRegistry = transactionRegistry;
        this.ticker = ticker;
        configService.addConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                weavingTimerEnabled = configService.getAdvancedConfig().weavingTimer();
            }
        });
        this.timerName = timerNameCache.getTimerName(OnlyForTheTimerName.class);
    }

    public void checkForDeadlockedActiveWeaving() {
        long currTick = ticker.read();
        List<Long> threadIds = Lists.newArrayList();
        for (ActiveWeaving activeWeaving : activeWeavings) {
            if (NANOSECONDS.toSeconds(currTick - activeWeaving.startTick) > 5) {
                threadIds.add(activeWeaving.threadId);
            }
        }
        if (!threadIds.isEmpty()) {
            checkForDeadlockedActiveWeaving(threadIds);
        }
    }

    byte /*@Nullable*/ [] weave(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        if (weavingDisabledForLoggingDeadlock) {
            return null;
        }
        long startTick = ticker.read();
        TimerImpl weavingTimer = startWeavingTimer(startTick);
        SelfRemovableEntry activeWeavingEntry =
                activeWeavings.add(new ActiveWeaving(Thread.currentThread().getId(), startTick));
        try {
            logger.trace("transform(): className={}", className);
            byte[] transformedBytes = weaveUnderTimer(classBytes, className, codeSource, loader);
            if (transformedBytes != null) {
                logger.debug("transform(): transformed {}", className);
            }
            return transformedBytes;
        } finally {
            activeWeavingEntry.remove();
            if (weavingTimer != null) {
                weavingTimer.stop();
            }
        }
    }

    private @Nullable TimerImpl startWeavingTimer(long startTick) {
        if (!weavingTimerEnabled) {
            return null;
        }
        ThreadContextImpl threadContext = transactionRegistry.getCurrentThreadContextHolder().get();
        if (threadContext == null) {
            return null;
        }
        TimerImpl currentTimer = threadContext.getCurrentTimer();
        if (currentTimer == null) {
            return null;
        }
        return currentTimer.startNestedTimer(timerName, startTick);
    }

    private byte /*@Nullable*/ [] weaveUnderTimer(byte[] classBytes, String className,
            @Nullable CodeSource codeSource, @Nullable ClassLoader loader) {
        List<Advice> advisors = analyzedWorld.mergeInstrumentationAnnotations(this.advisors.get(),
                classBytes, loader, className);
        ThinClassVisitor accv = new ThinClassVisitor();
        new ClassReader(classBytes).accept(accv, ClassReader.SKIP_FRAMES + ClassReader.SKIP_CODE);
        byte[] maybeProcessedBytes = null;
        if (className.equals(JBOSS_WELD_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new JBossWeldHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.EXPAND_FRAMES);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(JBOSS_MODULES_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new JBossModulesHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.EXPAND_FRAMES);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(FELIX_OSGI_HACK_CLASS_NAME)
                || className.equals(FELIX3_OSGI_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new FelixOsgiHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.EXPAND_FRAMES);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ECLIPSE_OSGI_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new EclipseOsgiHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.EXPAND_FRAMES);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(JBOSS4_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new JBoss4HackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.EXPAND_FRAMES);
            maybeProcessedBytes = cw.toByteArray();
        }
        ClassAnalyzer classAnalyzer = new ClassAnalyzer(accv.getThinClass(), advisors, shimTypes,
                mixinTypes, loader, analyzedWorld, codeSource, classBytes);
        classAnalyzer.analyzeMethods();
        if (!classAnalyzer.isWeavingRequired()) {
            analyzedWorld.add(classAnalyzer.getAnalyzedClass(), loader);
            return maybeProcessedBytes;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        WeavingClassVisitor cv =
                new WeavingClassVisitor(cw, loader, classAnalyzer.getAnalyzedClass(),
                        classAnalyzer.getMethodsThatOnlyNowFulfillAdvice(),
                        classAnalyzer.getMatchedShimTypes(), classAnalyzer.getMatchedMixinTypes(),
                        classAnalyzer.getMethodAdvisors(), analyzedWorld);
        ClassReader cr =
                new ClassReader(maybeProcessedBytes == null ? classBytes : maybeProcessedBytes);
        try {
            cr.accept(new JSRInlinerClassVisitor(cv), ClassReader.EXPAND_FRAMES);
        } catch (RuntimeException e) {
            logger.error("unable to weave {}: {}", className, e.getMessage(), e);
            try {
                File tempFile = getTempFile(className, "glowroot-weaving-error-", ".class");
                Files.write(classBytes, tempFile);
                logger.error("wrote bytecode to: {}", tempFile.getAbsolutePath());
            } catch (IOException f) {
                logger.error(f.getMessage(), f);
            }
            return null;
        }
        byte[] transformedBytes = cw.toByteArray();
        if (className.equals(DEBUG_CLASS_NAME)) {
            try {
                File tempFile = File.createTempFile("glowroot-transformed-", ".class");
                Files.write(transformedBytes, tempFile);
                logger.info("class file for {} (transformed) written to: {}", className,
                        tempFile.getAbsolutePath());
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
            logger.info("ASM for {} (transformed):\n{}", className, toASM(transformedBytes));

            ClassReader cr2 = new ClassReader(transformedBytes);
            ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            cr2.accept(cw2, ClassReader.SKIP_FRAMES);
            logger.info("ASM for {} (transformed + COMPUTE_FRAMES):\n{}", className,
                    toASM(cw2.toByteArray()));
        }
        return transformedBytes;
    }

    private void checkForDeadlockedActiveWeaving(List<Long> activeWeavingThreadIds) {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long /*@Nullable*/ [] deadlockedThreadIds = threadBean.findDeadlockedThreads();
        if (deadlockedThreadIds == null || Collections.disjoint(Longs.asList(deadlockedThreadIds),
                activeWeavingThreadIds)) {
            return;
        }
        // need to disable weaving, otherwise getThreadInfo can trigger class loading and itself get
        // blocked by the deadlocked threads
        weavingDisabledForLoggingDeadlock = true;
        try {
            @Nullable
            ThreadInfo[] threadInfos = threadBean.getThreadInfo(deadlockedThreadIds,
                    threadBean.isObjectMonitorUsageSupported(), false);
            StringBuilder sb = new StringBuilder();
            for (ThreadInfo threadInfo : threadInfos) {
                if (threadInfo != null) {
                    sb.append('\n');
                    appendThreadInfo(sb, threadInfo);
                }
            }
            logger.error("deadlock detected in class weaving, please report to the Glowroot"
                    + " project:\n{}", sb);
            // no need to keep checking for (and logging) deadlocked active weaving
            throw new TerminateSubsequentExecutionsException();
        } finally {
            weavingDisabledForLoggingDeadlock = false;
        }
    }

    private static String toASM(byte[] transformedBytes) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ClassReader cr = new ClassReader(transformedBytes);
        TraceClassVisitor tcv = new TraceClassVisitor(null, new ASMifier(), pw);
        cr.accept(tcv, ClassReader.EXPAND_FRAMES);
        pw.close();
        return sw.toString();
    }

    private static File getTempFile(String className, String prefix, String suffix) {
        String tmpDirProperty = StandardSystemProperty.JAVA_IO_TMPDIR.value();
        File tmpDir = tmpDirProperty == null ? new File(".") : new File(tmpDirProperty);
        String simpleName;
        int index = className.lastIndexOf('/');
        if (index == -1) {
            simpleName = className;
        } else {
            simpleName = className.substring(index + 1);
        }
        return new File(tmpDir, prefix + simpleName + suffix);
    }

    private static void appendThreadInfo(StringBuilder sb, ThreadInfo threadInfo) {
        sb.append('"');
        sb.append(threadInfo.getThreadName());
        sb.append("\" #");
        sb.append(threadInfo.getThreadId());
        sb.append("\n   java.lang.Thread.State: ");
        sb.append(threadInfo.getThreadState().name());
        sb.append('\n');
        LockInfo lockInfo = threadInfo.getLockInfo();
        StackTraceElement[] stackTrace = threadInfo.getStackTrace();
        for (int i = 0; i < stackTrace.length; i++) {
            StackTraceElement stackTraceElement = stackTrace[i];
            sb.append("        ");
            sb.append(stackTraceElement);
            sb.append('\n');
            if (i == 0 && lockInfo != null) {
                Thread.State threadState = threadInfo.getThreadState();
                switch (threadState) {
                    case BLOCKED:
                        sb.append("        -  blocked on ");
                        sb.append(lockInfo);
                        sb.append('\n');
                        break;
                    case WAITING:
                    case TIMED_WAITING:
                        sb.append("        -  waiting on ");
                        sb.append(lockInfo);
                        sb.append('\n');
                        break;
                    default:
                        break;
                }
            }
            for (MonitorInfo monitorInfo : threadInfo.getLockedMonitors()) {
                if (monitorInfo.getLockedStackDepth() == i) {
                    sb.append("        -  locked ");
                    sb.append(monitorInfo);
                    sb.append('\n');
                }
            }
        }
    }

    private static class ActiveWeaving {

        private final long threadId;
        private final long startTick;

        private ActiveWeaving(long threadId, long startTick) {
            this.threadId = threadId;
            this.startTick = startTick;
        }
    }

    private static class JSRInlinerClassVisitor extends ClassVisitor {

        private JSRInlinerClassVisitor(ClassVisitor cv) {
            super(ASM6, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv =
                    checkNotNull(cv).visitMethod(access, name, desc, signature, exceptions);
            return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
        }
    }

    private static class JBossWeldHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        JBossWeldHackClassVisitor(ClassWriter cw) {
            super(ASM6, cw);
            this.cw = cw;
        }

        @Override
        public @Nullable MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("checkDelegateType")
                    && desc.equals("(Ljavax/enterprise/inject/spi/Decorator;)V")) {
                return new JBossWeldHackMethodVisitor(mv);
            } else {
                return mv;
            }
        }
    }

    private static class JBossWeldHackMethodVisitor extends MethodVisitor {

        private JBossWeldHackMethodVisitor(MethodVisitor mv) {
            super(ASM6, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            if (name.equals("getDecoratedTypes") && desc.equals("()Ljava/util/Set;")) {
                super.visitMethodInsn(INVOKESTATIC,
                        "org/glowroot/agent/weaving/GeneratedBytecodeUtil",
                        "stripGlowrootTypes", "(Ljava/util/Set;)Ljava/util/Set;", false);
            }
        }
    }

    private static class JBossModulesHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        JBossModulesHackClassVisitor(ClassWriter cw) {
            super(ASM6, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("<clinit>")) {
                return new JBossModulesHackMethodVisitor(mv);
            } else {
                return mv;
            }
        }
    }

    private static class JBossModulesHackMethodVisitor extends MethodVisitor {

        private JBossModulesHackMethodVisitor(MethodVisitor mv) {
            super(ASM6, mv);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (name.equals("systemPackages") && desc.equals("[Ljava/lang/String;")) {
                visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/weaving/GeneratedBytecodeUtil",
                        "appendToJBossModulesSystemPkgs",
                        "([Ljava/lang/String;)[Ljava/lang/String;", false);
            }
            super.visitFieldInsn(opcode, owner, name, desc);
        }
    }

    private static class FelixOsgiHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;
        // this hack is used for both org.apache.felix.framework.BundleWiringImpl (felix 4.0.0+)
        // and org.apache.felix.framework.ModuleImpl (prior to felix 4.0.0)
        private @Nullable String className;

        FelixOsgiHackClassVisitor(ClassWriter cw) {
            super(ASM6, cw);
            this.cw = cw;
        }

        @Override
        public void visit(int version, int access, String name, @Nullable String signature,
                @Nullable String superName, String /*@Nullable*/ [] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            this.className = name;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("shouldBootDelegate") && desc.equals("(Ljava/lang/String;)Z")) {
                return new FelixOsgiHackMethodVisitor(checkNotNull(className), mv, access, name,
                        desc);
            } else {
                return mv;
            }
        }
    }

    private static class FelixOsgiHackMethodVisitor extends AdviceAdapter {

        private final String ownerName;

        private FelixOsgiHackMethodVisitor(String ownerName, MethodVisitor mv, int access,
                String name, String desc) {
            super(ASM6, mv, access, name, desc);
            this.ownerName = ownerName;
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 1);
            visitLdcInsn("org.glowroot.");
            visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            Label label = new Label();
            visitJumpInsn(IFEQ, label);
            visitInsn(ICONST_1);
            visitInsn(IRETURN);
            visitLabel(label);
            Object[] locals = new Object[] {ownerName, "java/lang/String"};
            visitFrame(F_NEW, locals.length, locals, 0, new Object[0]);
        }
    }

    private static class EclipseOsgiHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        EclipseOsgiHackClassVisitor(ClassWriter cw) {
            super(ASM6, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("isBootDelegationPackage") && desc.equals("(Ljava/lang/String;)Z")) {
                return new EclipseOsgiHackMethodVisitor(mv, access, name, desc);
            } else {
                return mv;
            }
        }
    }

    private static class EclipseOsgiHackMethodVisitor extends AdviceAdapter {

        private EclipseOsgiHackMethodVisitor(MethodVisitor mv, int access, String name,
                String desc) {
            super(ASM6, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 1);
            visitLdcInsn("org.glowroot.");
            visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "startsWith",
                    "(Ljava/lang/String;)Z", false);
            Label label = new Label();
            visitJumpInsn(IFEQ, label);
            visitInsn(ICONST_1);
            visitInsn(IRETURN);
            visitLabel(label);
            Object[] locals = new Object[] {ECLIPSE_OSGI_HACK_CLASS_NAME, "java/lang/String"};
            visitFrame(F_NEW, locals.length, locals, 0, new Object[0]);
        }
    }

    private static class JBoss4HackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        JBoss4HackClassVisitor(ClassWriter cw) {
            super(ASM6, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("internalInitURLHandlers") && desc.equals("()V")) {
                return new JBoss4HackMethodVisitor(mv, access, name, desc);
            } else {
                return mv;
            }
        }
    }

    private static class JBoss4HackMethodVisitor extends AdviceAdapter {

        private JBoss4HackMethodVisitor(MethodVisitor mv, int access, String name, String desc) {
            super(ASM6, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            // these classes can be initialized inside of ClassFileTransformer.transform(), via
            // Resources.toByteArray(url) inside of AnalyzedWorld.createAnalyzedClass()
            // because jboss 4.x registers org.jboss.net.protocol.URLStreamHandlerFactory to handle
            // "file" and "resource" URLs
            //
            // these classes can not be initialized in PreInitializeWeavingClasses since they are
            // not accessible from the bootstrap or system class loader, and thus, this hack
            Label l0 = new Label();
            Label l1 = new Label();
            Label l2 = new Label();
            mv.visitTryCatchBlock(l0, l1, l2, "java/lang/Throwable");
            mv.visitLabel(l0);
            visitClassForName("org.jboss.net.protocol.file.Handler");
            visitClassForName("org.jboss.net.protocol.file.FileURLConnection");
            visitClassForName("org.jboss.net.protocol.resource.Handler");
            visitClassForName("org.jboss.net.protocol.resource.ResourceURLConnection");
            mv.visitLabel(l1);
            Label l3 = new Label();
            mv.visitJumpInsn(GOTO, l3);
            mv.visitLabel(l2);
            if (logger.isDebugEnabled()) {
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V",
                        false);
            } else {
                mv.visitInsn(POP);
            }
            mv.visitLabel(l3);
        }

        private void visitClassForName(String className) {
            mv.visitLdcInsn(className);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;", false);
            mv.visitInsn(POP);
        }
    }

    @Pointcut(className = "", methodName = "", methodParameterTypes = {},
            timerName = "glowroot weaving")
    private static class OnlyForTheTimerName {
        private OnlyForTheTimerName() {}
    }
}
