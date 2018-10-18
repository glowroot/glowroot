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
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.security.CodeSource;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Supplier;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.primitives.Longs;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
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
import org.glowroot.agent.weaving.ClassLoaders.LazyDefinedClass;
import org.glowroot.common.util.ScheduledRunnable.TerminateSubsequentExecutionsException;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.V1_6;

public class Weaver {

    private static final Logger logger = LoggerFactory.getLogger(Weaver.class);

    // useful for debugging java.lang.VerifyError and java.lang.ClassFormatError
    private static final @Nullable String DEBUG_CLASS_NAME;

    static {
        String debugClassName = System.getProperty("glowroot.debug.className");
        if (debugClassName == null) {
            DEBUG_CLASS_NAME = null;
        } else {
            DEBUG_CLASS_NAME = ClassNames.toInternalName(debugClassName);
        }
    }

    private final Supplier<List<Advice>> advisors;
    private final ImmutableList<ShimType> shimTypes;
    private final ImmutableList<MixinType> mixinTypes;
    private final AnalyzedWorld analyzedWorld;
    private final TransactionRegistry transactionRegistry;
    private final Ticker ticker;
    private final TimerName timerName;

    private volatile boolean weavingTimerEnabled;

    private volatile boolean noLongerNeedToWeaveMainMethods;

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

    public void setNoLongerNeedToWeaveMainMethods() {
        noLongerNeedToWeaveMainMethods = true;
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
            @Nullable Class<?> classBeingRedefined, @Nullable CodeSource codeSource,
            @Nullable ClassLoader loader) {
        if (weavingDisabledForLoggingDeadlock) {
            return null;
        }
        long startTick = ticker.read();
        TimerImpl weavingTimer = startWeavingTimer(startTick);
        SelfRemovableEntry activeWeavingEntry =
                activeWeavings.add(new ActiveWeaving(Thread.currentThread().getId(), startTick));
        try {
            logger.trace("transform(): className={}", className);
            byte[] transformedBytes =
                    weaveUnderTimer(classBytes, className, classBeingRedefined, codeSource, loader);
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
        ThreadContextImpl threadContext =
                (ThreadContextImpl) transactionRegistry.getCurrentThreadContextHolder().get();
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
            @Nullable Class<?> classBeingRedefined, @Nullable CodeSource codeSource,
            @Nullable ClassLoader loader) {
        List<Advice> advisors = AnalyzedWorld.mergeInstrumentationAnnotations(this.advisors.get(),
                classBytes, loader, className);
        ThinClassVisitor accv = new ThinClassVisitor();
        new ClassReader(classBytes).accept(accv, ClassReader.SKIP_FRAMES + ClassReader.SKIP_CODE);
        boolean frames = accv.getMajorVersion() >= V1_6;
        int expandFrames = frames ? ClassReader.EXPAND_FRAMES : 0;
        byte[] maybeProcessedBytes = null;
        if (accv.isConstructorPointcut()) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new PointcutClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ImportantClassNames.JBOSS_WELD_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new JBossWeldHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ImportantClassNames.JBOSS_MODULES_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new JBossModulesHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ImportantClassNames.JBOSS_URL_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new JBossUrlHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ImportantClassNames.FELIX_OSGI_HACK_CLASS_NAME)
                || className.equals(ImportantClassNames.FELIX3_OSGI_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new OsgiHackClassVisitor(cw, className, "shouldBootDelegate");
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ImportantClassNames.ECLIPSE_OSGI_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new OsgiHackClassVisitor(cw, className, "isBootDelegationPackage");
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ImportantClassNames.OPENEJB_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new OpenEJBHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        } else if (className.equals(ImportantClassNames.HIKARI_CP_PROXY_HACK_CLASS_NAME)) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassVisitor cv = new HikariCpProxyHackClassVisitor(cw);
            ClassReader cr = new ClassReader(classBytes);
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            maybeProcessedBytes = cw.toByteArray();
        }
        ClassAnalyzer classAnalyzer = new ClassAnalyzer(accv.getThinClass(), advisors, shimTypes,
                mixinTypes, loader, analyzedWorld, codeSource, classBytes, classBeingRedefined,
                noLongerNeedToWeaveMainMethods);
        classAnalyzer.analyzeMethods();
        if (!classAnalyzer.isWeavingRequired()) {
            analyzedWorld.add(classAnalyzer.getAnalyzedClass(), loader);
            return maybeProcessedBytes;
        }
        List<ShimType> matchedShimTypes = classAnalyzer.getMatchedShimTypes();
        List<MixinType> reweavableMatchedMixinTypes =
                classAnalyzer.getMatchedReweavableMixinTypes();
        if (classBeingRedefined != null
                && (!matchedShimTypes.isEmpty() || !reweavableMatchedMixinTypes.isEmpty())) {
            Set<String> interfaceNames = Sets.newHashSet();
            for (Class<?> iface : classBeingRedefined.getInterfaces()) {
                interfaceNames.add(iface.getName());
            }
            matchedShimTypes = Lists.newArrayList(matchedShimTypes);
            for (ShimType matchedShimType : matchedShimTypes) {
                if (!interfaceNames.contains(matchedShimType.iface().getClassName())) {
                    // re-weaving would fail with "attempted to change superclass or interfaces"
                    logger.error("not reweaving {} because cannot add shim type: {}",
                            ClassNames.fromInternalName(className),
                            matchedShimType.iface().getClassName());
                    return null;
                }
            }
            for (MixinType matchedMixinType : reweavableMatchedMixinTypes) {
                for (Type mixinInterface : matchedMixinType.interfaces()) {
                    if (!interfaceNames.contains(mixinInterface.getClassName())) {
                        // re-weaving would fail with "attempted to change superclass or interfaces"
                        logger.error("not reweaving {} because cannot add mixin type: {}",
                                ClassNames.fromInternalName(className),
                                mixinInterface.getClassName());
                        return null;
                    }
                }
            }
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        WeavingClassVisitor cv = new WeavingClassVisitor(cw, loader, frames,
                noLongerNeedToWeaveMainMethods, classAnalyzer.getAnalyzedClass(),
                classAnalyzer.getMethodsThatOnlyNowFulfillAdvice(), matchedShimTypes,
                reweavableMatchedMixinTypes, classAnalyzer.getMethodAdvisors(), analyzedWorld);
        ClassReader cr =
                new ClassReader(maybeProcessedBytes == null ? classBytes : maybeProcessedBytes);
        byte[] transformedBytes;
        try {
            cr.accept(new JSRInlinerClassVisitor(cv), expandFrames);
            // ClassWriter.toByteArray() can throw exception also, see issue #370
            transformedBytes = cw.toByteArray();
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
        if (className.equals(DEBUG_CLASS_NAME)) {
            try {
                File tempFile = File.createTempFile("glowroot-transformed-", ".class");
                Files.write(transformedBytes, tempFile);
                logger.info("class file for {} (transformed) written to: {}", className,
                        tempFile.getAbsolutePath());
                tempFile = File.createTempFile("glowroot-original-", ".class");
                Files.write(classBytes, tempFile);
                logger.info("class file for {} (original) written to: {}", className,
                        tempFile.getAbsolutePath());
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
        if (loader != null) {
            try {
                for (Advice usedAdvice : cv.getUsedAdvisors()) {
                    LazyDefinedClass nonBootstrapLoaderAdviceClass =
                            usedAdvice.nonBootstrapLoaderAdviceClass();
                    if (nonBootstrapLoaderAdviceClass != null) {
                        ClassLoaders.defineClassIfNotExists(nonBootstrapLoaderAdviceClass, loader);
                    }
                }
            } catch (Exception e) {
                logger.error("unable to weave {}: {}", className, e.getMessage(), e);
                return null;
            }
        }
        return transformedBytes;
    }

    private void checkForDeadlockedActiveWeaving(List<Long> activeWeavingThreadIds) {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedThreadIds = threadBean.findDeadlockedThreads();
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

    private static class JBossWeldHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        private JBossWeldHackClassVisitor(ClassWriter cw) {
            super(ASM7, cw);
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
            super(ASM7, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            if (name.equals("getDecoratedTypes") && desc.equals("()Ljava/util/Set;")) {
                super.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Util",
                        "stripGlowrootTypes", "(Ljava/util/Set;)Ljava/util/Set;", false);
            }
        }
    }

    private static class JBossModulesHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        private JBossModulesHackClassVisitor(ClassWriter cw) {
            super(ASM7, cw);
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
            super(ASM7, mv);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (name.equals("systemPackages") && desc.equals("[Ljava/lang/String;")) {
                visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Util",
                        "appendToJBossModulesSystemPkgs",
                        "([Ljava/lang/String;)[Ljava/lang/String;", false);
            }
            super.visitFieldInsn(opcode, owner, name, desc);
        }
    }

    private static class JBossUrlHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        private JBossUrlHackClassVisitor(ClassWriter cw) {
            super(ASM7, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("<clinit>") && desc.equals("()V")) {
                return new JBossUrlHackMethodVisitor(mv, access, name, desc);
            } else {
                return mv;
            }
        }
    }

    private static class JBossUrlHackMethodVisitor extends AdviceAdapter {

        private JBossUrlHackMethodVisitor(MethodVisitor mv, int access, String name,
                String desc) {
            super(ASM7, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            // these classes can be initialized inside of ClassFileTransformer.transform(), via
            // Resources.toByteArray(url) inside of AnalyzedWorld.createAnalyzedClass()
            // because jboss registers org.jboss.net.protocol.URLStreamHandlerFactory to handle
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

    private static class OsgiHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;
        // this hack is used for
        // org.apache.felix.framework.BundleWiringImpl.shouldBootDelegate() (felix 4.0.0+)
        // org.apache.felix.framework.ModuleImpl.shouldBootDelegate() (prior to felix 4.0.0)
        // org.eclipse.osgi.internal.framework.EquinoxContainer.isBootDelegationPackage()
        private final String className;
        private final String methodName;

        private OsgiHackClassVisitor(ClassWriter cw, String className, String methodName) {
            super(ASM7, cw);
            this.cw = cw;
            this.className = className;
            this.methodName = methodName;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals(methodName) && desc.equals("(Ljava/lang/String;)Z")) {
                return new OsgiHackMethodVisitor(className, mv, access, name, desc);
            } else {
                return mv;
            }
        }
    }

    private static class OsgiHackMethodVisitor extends AdviceAdapter {

        private final String ownerName;

        private OsgiHackMethodVisitor(String ownerName, MethodVisitor mv, int access,
                String name, String desc) {
            super(ASM7, mv, access, name, desc);
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

    private static class OpenEJBHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        private OpenEJBHackClassVisitor(ClassWriter cw) {
            super(ASM7, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("reloadConfig") && desc.equals("()V")) {
                return new OpenEJBHackMethodVisitor(mv, access, name, desc);
            } else {
                return mv;
            }
        }
    }

    private static class OpenEJBHackMethodVisitor extends AdviceAdapter {

        private OpenEJBHackMethodVisitor(MethodVisitor mv, int access, String name, String desc) {
            super(ASM7, mv, access, name, desc);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == RETURN) {
                visitFieldInsn(GETSTATIC, ImportantClassNames.OPENEJB_HACK_CLASS_NAME,
                        "FORCED_SKIP", "Ljava/util/Collection;");
                visitLdcInsn("org.glowroot.");
                visitMethodInsn(INVOKEINTERFACE, "java/util/Collection", "add",
                        "(Ljava/lang/Object;)Z", true);
                pop();
            }
        }
    }

    private static class HikariCpProxyHackClassVisitor extends ClassVisitor {

        private final ClassWriter cw;

        private HikariCpProxyHackClassVisitor(ClassWriter cw) {
            super(ASM7, cw);
            this.cw = cw;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                @Nullable String signature, String /*@Nullable*/ [] exceptions) {
            MethodVisitor mv = cw.visitMethod(access, name, desc, signature, exceptions);
            if (name.equals("generateProxyClass")) {
                return new HikariCpProxyHackMethodVisitor(mv);
            } else {
                return mv;
            }
        }
    }

    private static class HikariCpProxyHackMethodVisitor extends MethodVisitor {

        private HikariCpProxyHackMethodVisitor(MethodVisitor mv) {
            super(ASM7, mv);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc,
                boolean itf) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            if (owner.equals("com/zaxxer/hikari/util/ClassLoaderUtils")
                    && name.equals("getAllInterfaces")
                    && desc.equals("(Ljava/lang/Class;)Ljava/util/Set;")) {
                super.visitMethodInsn(INVOKESTATIC, "org/glowroot/agent/bytecode/api/Util",
                        "stripGlowrootClasses", "(Ljava/util/Set;)Ljava/util/Set;", false);
            }
        }
    }

    @Pointcut(className = "", methodName = "", methodParameterTypes = {},
            timerName = "glowroot weaving")
    private static class OnlyForTheTimerName {
        private OnlyForTheTimerName() {}
    }
}
