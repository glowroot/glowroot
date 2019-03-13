/*
 * Copyright 2011-2019 the original author or authors.
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
package org.glowroot.agent;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.bytecode.api.BytecodeServiceHolder;
import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.GlowrootAgentInitFactory;
import org.glowroot.agent.init.NonEmbeddedGlowrootAgentInit;
import org.glowroot.agent.init.PreCheckLoadedClasses.PreCheckClassFileTransformer;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.agent.weaving.Java9;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Version;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;

public class MainEntryPoint {

    private static final boolean PRE_CHECK_LOADED_CLASSES =
            Boolean.getBoolean("glowroot.debug.preCheckLoadedClasses");

    public static final boolean PRINT_CLASS_LOADING =
            Boolean.getBoolean("glowroot.debug.printClassLoading");

    // need to wait to init logger until after establishing logDir
    private static volatile @MonotonicNonNull Logger startupLogger;

    @OnlyUsedByTests
    private static @MonotonicNonNull GlowrootAgentInit glowrootAgentInit;

    private MainEntryPoint() {}

    public static void premain(Instrumentation instrumentation, Class<?>[] allPriorLoadedClasses,
            @Nullable File glowrootJarFile) {
        if (startupLogger != null) {
            // glowroot is already running, probably due to multiple glowroot -javaagent JVM args
            return;
        }
        // DO NOT USE ANY GUAVA CLASSES before initLogging() because they trigger loading of jul
        // (and thus org.glowroot.agent.jul.Logger and thus glowroot's shaded slf4j)
        PreCheckClassFileTransformer preCheckClassFileTransformer = null;
        Directories directories;
        try {
            if (PRE_CHECK_LOADED_CLASSES) {
                preCheckClassFileTransformer = new PreCheckClassFileTransformer();
                instrumentation.addTransformer(preCheckClassFileTransformer);
            }
            if (PRINT_CLASS_LOADING) {
                DebuggingClassFileTransformer transformer = new DebuggingClassFileTransformer();
                instrumentation.addTransformer(transformer, true);
                instrumentation
                        .retransformClasses(Class.forName("sun.misc.Launcher$AppClassLoader"));
                instrumentation.removeTransformer(transformer);
            }
            directories = new Directories(glowrootJarFile);
            // init logger as early as possible
            initLogging(directories.getConfDirs(), directories.getLogDir(),
                    directories.getLoggingLogstashJarFile(), instrumentation);
            PreCheckClassFileTransformer.initLogger();
            DebuggingClassFileTransformer.initLogger();
            if (directories.logStartupErrorMultiDirWithMissingAgentId()) {
                startupLogger
                        .error("Glowroot failed to start: multi.dir is true, but missing agent.id");
                return;
            }
            if (directories.getAgentDirLockCloseable() == null) {
                ImmutableMap<String, String> properties =
                        getGlowrootProperties(directories.getConfDirs());
                logAgentDirsLockedException(directories.getConfDir(),
                        new File(directories.getTmpDir(), ".lock"), properties);
                return;
            }
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            // also, don't use logger since not initialized yet
            System.err.println("Glowroot failed to start: " + t.getMessage());
            t.printStackTrace();
            return;
        }
        if (PRE_CHECK_LOADED_CLASSES) {
            if (AgentModule.logAnyImportantClassLoadedPriorToWeavingInit(allPriorLoadedClasses,
                    glowrootJarFile, true)) {
                List<String> classNames = Lists.newArrayList();
                for (Class<?> clazz : allPriorLoadedClasses) {
                    String className = clazz.getName();
                    if (!className.startsWith("[")) {
                        classNames.add(className);
                    }
                }
                Collections.sort(classNames);
                startupLogger.warn("PRE-CHECK: full list of classes already loaded: {}",
                        Joiner.on(", ").join(classNames));
            } else {
                startupLogger.info("PRE-CHECK: successful");
            }
        }
        try {
            instrumentation.addTransformer(new ManagementFactoryHackClassFileTransformer());
            // need to load ThreadMXBean before it's possible to start any transactions since
            // starting transactions depends on ThreadMXBean and so can lead to problems
            // (e.g. see FileInstrumentationPresentAtStartupIT)
            ManagementFactory.getThreadMXBean();
            // don't remove transformer in case the class is retransformed later
            if (JavaVersion.isGreaterThanOrEqualToJava9()) {
                Object baseModule = Java9.getModule(ClassLoader.class);
                Java9.grantAccessToGlowroot(instrumentation, baseModule);
                Java9.grantAccess(instrumentation, "org.glowroot.agent.weaving.ClassLoaders",
                        "java.lang.ClassLoader", false);
                Java9.grantAccess(instrumentation, "io.netty.util.internal.ReflectionUtil",
                        "java.nio.DirectByteBuffer", false);
                Java9.grantAccess(instrumentation, "io.netty.util.internal.ReflectionUtil",
                        "sun.nio.ch.SelectorImpl", false);
                instrumentation.addTransformer(new Java9HackClassFileTransformer());
                Class.forName("org.glowroot.agent.weaving.WeavingClassFileTransformer");
                // don't remove transformer in case the class is retransformed later
            }
            if (JavaVersion.isJ9Jvm() && JavaVersion.isJava6()) {
                instrumentation.addTransformer(new IbmJ9Java6HackClassFileTransformer());
                Class.forName("com.google.protobuf.UnsafeUtil");
                // don't remove transformer in case the class is retransformed later
            }
            ImmutableMap<String, String> properties =
                    getGlowrootProperties(directories.getConfDirs());
            start(directories, properties, instrumentation, preCheckClassFileTransformer);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot failed to start: {}", t.getMessage(), t);
            BytecodeServiceHolder.setGlowrootFailedToStart();
        }
    }

    public static void runOfflineViewer(Directories directories,
            GlowrootAgentInitFactory glowrootAgentInitFactory) {
        // initLogging() already called by OfflineViewer.main()
        checkNotNull(startupLogger);
        try {
            String version = Version.getVersion(MainEntryPoint.class);
            startupLogger.info("Glowroot version: {}", version);
            startupLogger.info("Java version: {}", getJavaVersion());
            ImmutableMap<String, String> properties =
                    getGlowrootProperties(directories.getConfDirs());
            glowrootAgentInitFactory.newGlowrootAgentInit(directories.getDataDir(), true, null)
                    .init(directories.getPluginsDir(), directories.getConfDirs(),
                            directories.getLogDir(), directories.getTmpDir(),
                            directories.getGlowrootJarFile(), properties, null, null, version,
                            checkNotNull(directories.getAgentDirLockCloseable()));
        } catch (Throwable t) {
            startupLogger.error("Glowroot cannot start: {}", t.getMessage(), t);
            return;
        }
    }

    @EnsuresNonNull("startupLogger")
    public static void initLogging(List<File> confDirs, File logDir,
            @Nullable File loggingLogstashJarFile, @Nullable Instrumentation instrumentation)
            throws IOException {
        if (loggingLogstashJarFile != null && instrumentation != null) {
            instrumentation
                    .appendToBootstrapClassLoaderSearch(new JarFile(loggingLogstashJarFile));
        }
        if (JavaVersion.isJava6() && "IBM J9 VM".equals(System.getProperty("java.vm.name"))
                && instrumentation != null) {
            instrumentation.addTransformer(new IbmJ9Java6HackClassFileTransformer2());
        }
        for (File confDir : confDirs) {
            File logbackXmlOverride = new File(confDir, "glowroot.logback.xml");
            if (logbackXmlOverride.exists()) {
                System.setProperty("glowroot.logback.configurationFile",
                        logbackXmlOverride.getAbsolutePath());
                break;
            }
        }
        String priorProperty = System.getProperty("glowroot.log.dir");
        System.setProperty("glowroot.log.dir", logDir.getPath());
        ClassLoader priorLoader = Thread.currentThread().getContextClassLoader();
        // setting the context class loader to only load from bootstrap class loader (by specifying
        // null parent class loader), otherwise logback will pick up and use a SAX parser on the
        // system classpath because SAXParserFactory.newInstance() checks the thread context class
        // loader for resource named META-INF/services/javax.xml.parsers.SAXParserFactory
        // (see the xerces dependency in glowroot-agent-integration-tests for testing this)
        Thread.currentThread().setContextClassLoader(new ClassLoader(null) {
            // overriding getResourceAsStream() is needed for JDK 6 since it still manages to
            // fallback and find the resource on the system class path otherwise
            @Override
            public @Nullable InputStream getResourceAsStream(String name) {
                if (name.equals("META-INF/services/javax.xml.parsers.SAXParserFactory")) {
                    return new ByteArrayInputStream(new byte[0]);
                }
                return null;
            }
        });
        try {
            startupLogger = LoggerFactory.getLogger("org.glowroot");
        } finally {
            Thread.currentThread().setContextClassLoader(priorLoader);
            if (priorProperty == null) {
                System.clearProperty("glowroot.log.dir");
            } else {
                System.setProperty("glowroot.log.dir", priorProperty);
            }
            System.clearProperty("glowroot.logback.configurationFile");
            // don't remove transformer in case the class is retransformed later
        }
        // TODO report checker framework issue that occurs without checkNotNull
        checkNotNull(startupLogger);
    }

    @RequiresNonNull("startupLogger")
    private static void start(Directories directories, Map<String, String> properties,
            @Nullable Instrumentation instrumentation,
            @Nullable PreCheckClassFileTransformer preCheckClassFileTransformer) throws Exception {
        String version = Version.getVersion(MainEntryPoint.class);
        startupLogger.info("Glowroot version: {}", version);
        startupLogger.info("Java version: {}", getJavaVersion());
        startupLogger.info("Java args: {}", getJvmArgs());
        glowrootAgentInit = createGlowrootAgentInit(directories, properties, instrumentation);
        glowrootAgentInit.init(directories.getPluginsDir(), directories.getConfDirs(),
                directories.getLogDir(), directories.getTmpDir(), directories.getGlowrootJarFile(),
                properties, instrumentation, preCheckClassFileTransformer, version,
                checkNotNull(directories.getAgentDirLockCloseable()));
    }

    @RequiresNonNull("startupLogger")
    private static GlowrootAgentInit createGlowrootAgentInit(Directories directories,
            Map<String, String> properties, @Nullable Instrumentation instrumentation)
            throws Exception {
        String collectorAddress = properties.get("glowroot.collector.address");
        Class<? extends Collector> customCollectorClass =
                loadCustomCollectorClass(directories.getGlowrootDir());
        Constructor<? extends Collector> collectorProxyConstructor = null;
        if (customCollectorClass != null) {
            try {
                collectorProxyConstructor = customCollectorClass.getConstructor(Collector.class);
            } catch (NoSuchMethodException e) {
                startupLogger.debug(e.getMessage(), e);
            }
        }
        if (customCollectorClass != null && collectorProxyConstructor == null) {
            // non-delegating custom class loader
            startupLogger.info("using collector: {}", customCollectorClass.getName());
            return new NonEmbeddedGlowrootAgentInit(null, null, customCollectorClass);
        }
        if (collectorAddress == null) {
            File embeddedCollectorJarFile = directories.getEmbeddedCollectorJarFile();

            ClassLoader embeddedLoader;
            if (embeddedCollectorJarFile == null) {
                embeddedLoader = ClassLoader.getSystemClassLoader();
            } else {
                embeddedLoader =
                        new URLClassLoader(new URL[] {embeddedCollectorJarFile.toURI().toURL()},
                                ClassLoader.getSystemClassLoader());
            }
            Class<?> factoryClass;
            try {
                factoryClass = Class.forName(
                        "org.glowroot.agent.embedded.init.EmbeddedGlowrootAgentInitFactory", true,
                        embeddedLoader);
            } catch (ClassNotFoundException e) {
                if (embeddedCollectorJarFile == null) {
                    startupLogger.error("missing lib/glowroot-embedded-collector.jar");
                }
                throw e;
            }
            GlowrootAgentInitFactory glowrootAgentInitFactory =
                    (GlowrootAgentInitFactory) factoryClass.newInstance();
            return glowrootAgentInitFactory.newGlowrootAgentInit(directories.getDataDir(), false,
                    customCollectorClass);
        }
        if (collectorAddress.startsWith("https://") && instrumentation != null) {
            String normalizedOsName = getNormalizedOsName();
            if (normalizedOsName == null) {
                throw new IllegalStateException("HTTPS connection to central collector is only"
                        + " supported on linux, windows and osx, detected os.name: "
                        + System.getProperty("os.name"));
            }
            File centralCollectorHttpsJarFile =
                    directories.getCentralCollectorHttpsJarFile(normalizedOsName);
            if (centralCollectorHttpsJarFile == null) {
                throw new IllegalStateException("Missing lib/glowroot-central-collector-https-"
                        + normalizedOsName + ".jar");
            }
            instrumentation
                    .appendToBootstrapClassLoaderSearch(new JarFile(centralCollectorHttpsJarFile));
            // also need to add to system class loader, otherwise native libraries under
            // META-INF/native are not found
            instrumentation
                    .appendToSystemClassLoaderSearch(new JarFile(centralCollectorHttpsJarFile));
        }
        String collectorAuthority = properties.get("glowroot.collector.authority");
        return new NonEmbeddedGlowrootAgentInit(collectorAddress, collectorAuthority,
                customCollectorClass);
    }

    private static ImmutableMap<String, String> getGlowrootProperties(List<File> confDirs)
            throws IOException {
        Map<String, String> properties = Maps.newHashMap();
        // iterate in reverse, so more specific conf dirs overlay on top of more general conf dirs
        ListIterator<File> i = confDirs.listIterator(confDirs.size());
        while (i.hasPrevious()) {
            PropertiesFiles.upgradeIfNeededAndLoadInto(i.previous(), properties);
        }
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String
                    && ((String) entry.getKey()).startsWith("glowroot.")) {
                String key = (String) entry.getKey();
                properties.put(key, (String) entry.getValue());
            }
        }
        return ImmutableMap.copyOf(properties);
    }

    @RequiresNonNull("startupLogger")
    private static void logAgentDirsLockedException(File confDir, File lockFile,
            Map<String, String> properties) {
        // this is common when stopping tomcat since 'catalina.sh stop' launches a java process
        // to stop the tomcat jvm, and it uses the same JAVA_OPTS environment variable that may
        // have been used to specify '-javaagent:glowroot.jar', in which case Glowroot tries
        // to start up, but it finds the h2 database is locked (by the tomcat jvm).
        // this can be avoided by using CATALINA_OPTS instead of JAVA_OPTS to specify
        // -javaagent:glowroot.jar, since CATALINA_OPTS is not used by the 'catalina.sh stop'.
        // however, when running tomcat from inside eclipse, the tomcat server adapter uses the
        // same 'VM arguments' for both starting and stopping tomcat, so this code path seems
        // inevitable at least in this case
        //
        // no need for logging in the special (but common) case described above
        if (!isTomcatStop()) {
            String extraExplanation = "";
            extraExplanation = ".  If you are trying to monitor multiple JVM processes on one box"
                    + " from the same agent installation, please see instructions for how to do"
                    + " this on the wiki: ";
            if (properties.containsKey("glowroot.collector.address")) {
                extraExplanation += "https://github.com/glowroot/glowroot/wiki/Agent-Installation"
                        + "-(for-Central-Collector)#monitoring-multiple-jvm-processes-on-one-box";
            } else {
                extraExplanation += "https://github.com/glowroot/glowroot/wiki/Agent-Installation"
                        + "-(with-Embedded-Collector)#monitoring-multiple-jvm-processes-on-one-box";
            }
            startupLogger.error("Glowroot failed to start, directory in use by another jvm process:"
                    + " {} (unable to obtain lock on {}){}", confDir.getAbsolutePath(),
                    lockFile.getAbsolutePath(), extraExplanation);
        }
    }

    private static boolean isTomcatStop() {
        return Objects.equal(System.getProperty("sun.java.command"),
                "org.apache.catalina.startup.Bootstrap stop");
    }

    private static @Nullable Class<? extends Collector> loadCustomCollectorClass(File glowrootDir)
            throws Exception {
        ClassLoader classLoader = MainEntryPoint.class.getClassLoader();
        Class<? extends Collector> collectorClass = loadCollectorClass(classLoader);
        if (collectorClass != null) {
            return collectorClass;
        }
        File servicesDir = new File(glowrootDir, "services");
        if (!servicesDir.exists()) {
            return null;
        }
        if (!servicesDir.isDirectory()) {
            return null;
        }
        File[] files = servicesDir.listFiles();
        if (files == null) {
            return null;
        }
        List<URL> urls = Lists.newArrayList();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                urls.add(file.toURI().toURL());
            }
        }
        if (urls.isEmpty()) {
            return null;
        }
        URLClassLoader servicesClassLoader = new URLClassLoader(urls.toArray(new URL[0]));
        return loadCollectorClass(servicesClassLoader);
    }

    private static @Nullable Class<? extends Collector> loadCollectorClass(
            @Nullable ClassLoader classLoader) throws Exception {
        InputStream in;
        if (classLoader == null) {
            in = ClassLoader.getSystemResourceAsStream(
                    "META-INF/services/org.glowroot.agent.collector.Collector");
        } else {
            in = classLoader.getResourceAsStream(
                    "META-INF/services/org.glowroot.agent.collector.Collector");
        }
        if (in == null) {
            return null;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8));
        try {
            String line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    break;
                }
                line = reader.readLine();
            }
            if (line == null) {
                return null;
            }
            Class<?> clazz = Class.forName(line, false, classLoader);
            return clazz.asSubclass(Collector.class);
        } finally {
            reader.close();
        }
    }

    private static @Nullable String getNormalizedOsName() {
        String osName = System.getProperty("os.name");
        if (osName == null) {
            return null;
        }
        // using logic from https://github.com/trustin/os-maven-plugin#property-osdetectedname
        String lowerOsName = osName.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "");
        if (lowerOsName.startsWith("linux")) {
            if (new File("/etc/alpine-release").exists()) {
                return "linux-alpine";
            } else {
                return "linux";
            }
        } else if (lowerOsName.startsWith("windows")) {
            return "windows";
        } else if (lowerOsName.startsWith("macosx") || lowerOsName.startsWith("osx")) {
            return "linux";
        } else {
            return null;
        }
    }

    private static StringBuilder getJavaVersion() {
        StringBuilder sb = new StringBuilder();
        sb.append(StandardSystemProperty.JAVA_VERSION.value());
        String vendor = System.getProperty("java.vm.vendor");
        String os = System.getProperty("os.name");
        boolean appendVendor = !Strings.isNullOrEmpty(vendor);
        boolean appendOS = !Strings.isNullOrEmpty(os);
        if (appendVendor && appendOS) {
            sb.append(" (");
            if (appendVendor) {
                sb.append(vendor);
                if (appendOS) {
                    sb.append(" / ");
                }
            }
            if (appendOS) {
                sb.append(os);
            }
            sb.append(")");
        }
        return sb;
    }

    private static StringBuilder getJvmArgs() {
        StringBuilder sb = new StringBuilder();
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!jvmArg.startsWith("-D")) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(jvmArg);
            }
        }
        return sb;
    }

    @OnlyUsedByTests
    public static void start(Map<String, String> properties) throws Exception {
        String testDirPath = checkNotNull(properties.get("glowroot.test.dir"));
        File testDir = new File(testDirPath);
        // init logger as early as possible
        initLogging(Arrays.asList(testDir), testDir, null, null);
        Directories directories = new Directories(testDir, false);
        start(directories, properties, null, null);
    }

    @OnlyUsedByTests
    public static @Nullable GlowrootAgentInit getGlowrootAgentInit() {
        return glowrootAgentInit;
    }
}
