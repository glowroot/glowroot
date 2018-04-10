/*
 * Copyright 2011-2018 the original author or authors.
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.init.AgentDirsLocking.AgentDirsLockedException;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.init.GlowrootAgentInitFactory;
import org.glowroot.agent.init.NonEmbeddedGlowrootAgentInit;
import org.glowroot.agent.init.PreCheckLoadedClasses.DebugClassFileTransformer;
import org.glowroot.agent.util.AppServerDetection;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.agent.weaving.Java9;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.PropertiesFiles;
import org.glowroot.common.util.Version;

import static com.google.common.base.Preconditions.checkNotNull;

public class MainEntryPoint {

    private static final boolean PRE_CHECK_LOADED_CLASSES =
            Boolean.getBoolean("glowroot.debug.preCheckLoadedClasses");

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
        DebugClassFileTransformer debugClassFileTransformer = null;
        if (PRE_CHECK_LOADED_CLASSES) {
            debugClassFileTransformer = new DebugClassFileTransformer();
            instrumentation.addTransformer(debugClassFileTransformer);
        }
        // DO NOT USE ANY GUAVA CLASSES before initLogging() because they trigger loading of jul
        // (and thus org.glowroot.agent.jul.Logger and thus glowroot's shaded slf4j)
        Directories directories;
        try {
            directories = new Directories(glowrootJarFile);
            // init logger as early as possible
            initLogging(directories.getConfDir(), directories.getSharedConfDir(),
                    directories.getLogDir(), instrumentation);
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            // also, don't use logger since not initialized yet
            System.err.println("Glowroot not started: " + t.getMessage());
            t.printStackTrace();
            return;
        }
        if (PRE_CHECK_LOADED_CLASSES) {
            if (AgentModule.logJavaClassAlreadyLoadedWarningIfNeeded(allPriorLoadedClasses,
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
        if (JavaVersion.isGreaterThanOrEqualToJava9()) {
            try {
                Object baseModule = Java9.getModule(ClassLoader.class);
                Java9.grantAccessToGlowroot(instrumentation, baseModule);
                Java9.grantAccess(instrumentation, "org.glowroot.agent.weaving.ClassLoaders",
                        "java.lang.ClassLoader", false);
                Java9.grantAccess(instrumentation, "io.netty.util.internal.ReflectionUtil",
                        "java.nio.DirectByteBuffer", false);
                Java9.grantAccess(instrumentation, "io.netty.util.internal.ReflectionUtil",
                        "sun.nio.ch.SelectorImpl", false);
                ClassFileTransformer transformer = new Java9HackClassFileTransformer();
                instrumentation.addTransformer(transformer);
                Class.forName("org.glowroot.agent.weaving.WeavingClassFileTransformer");
                instrumentation.removeTransformer(transformer);
            } catch (Throwable t) {
                // log error but don't re-throw which would prevent monitored app from starting
                startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
                return;
            }
        }
        try {
            if (AppServerDetection.isIbmJvm() && JavaVersion.isJava6()) {
                ClassFileTransformer transformer = new IbmJava6HackClassFileTransformer();
                instrumentation.addTransformer(transformer);
                Class.forName("com.google.protobuf.UnsafeUtil");
                instrumentation.removeTransformer(transformer);
            }
            ImmutableMap<String, String> properties =
                    getGlowrootProperties(directories.getConfDir(), directories.getSharedConfDir());
            start(directories, properties, instrumentation, debugClassFileTransformer);
        } catch (AgentDirsLockedException e) {
            logAgentDirsLockedException(directories.getConfDir(), e.getLockFile());
        } catch (Throwable t) {
            // log error but don't re-throw which would prevent monitored app from starting
            startupLogger.error("Glowroot not started: {}", t.getMessage(), t);
        }
    }

    public static void runViewer(Directories directories,
            GlowrootAgentInitFactory glowrootAgentInitFactory) throws InterruptedException {
        // initLogging() already called by OfflineViewer.main()
        checkNotNull(startupLogger);
        String version;
        try {
            version = Version.getVersion(MainEntryPoint.class);
            startupLogger.info("Glowroot version: {}", version);
            startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());
            ImmutableMap<String, String> properties =
                    getGlowrootProperties(directories.getConfDir(), directories.getSharedConfDir());
            glowrootAgentInitFactory.newGlowrootAgentInit(directories.getDataDir(), true, null)
                    .init(directories.getPluginsDir(), directories.getConfDir(),
                            directories.getSharedConfDir(), directories.getLogDir(),
                            directories.getTmpDir(), directories.getGlowrootJarFile(), properties,
                            null, null, version);
        } catch (AgentDirsLockedException e) {
            logAgentDirsLockedException(directories.getConfDir(), e.getLockFile());
            return;
        } catch (Throwable t) {
            startupLogger.error("Glowroot cannot start: {}", t.getMessage(), t);
            return;
        }
        // Glowroot does not create any non-daemon threads, so need to block jvm from exiting when
        // running the viewer
        Thread.sleep(Long.MAX_VALUE);
    }

    @EnsuresNonNull("startupLogger")
    public static void initLogging(File confDir, @Nullable File sharedConfDir, File logDir,
            @Nullable Instrumentation instrumentation) {
        ClassFileTransformer transformer = null;
        if (JavaVersion.isJava6() && "IBM J9 VM".equals(System.getProperty("java.vm.name"))
                && instrumentation != null) {
            transformer = new IbmJava6HackClassFileTransformer2();
            instrumentation.addTransformer(transformer);
        }
        File logbackXmlOverride = new File(confDir, "glowroot.logback.xml");
        if (logbackXmlOverride.exists()) {
            System.setProperty("glowroot.logback.configurationFile",
                    logbackXmlOverride.getAbsolutePath());
        } else if (sharedConfDir != null) {
            logbackXmlOverride = new File(sharedConfDir, "glowroot.logback.xml");
            if (logbackXmlOverride.exists()) {
                System.setProperty("glowroot.logback.configurationFile",
                        logbackXmlOverride.getAbsolutePath());
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
            if (transformer != null) {
                // checkNotNull is safe b/c instrumentation is non-null when transformer is non-null
                checkNotNull(instrumentation).removeTransformer(transformer);
            }
        }
        // TODO report checker framework issue that occurs without checkNotNull
        checkNotNull(startupLogger);
    }

    @RequiresNonNull("startupLogger")
    private static void start(Directories directories, Map<String, String> properties,
            @Nullable Instrumentation instrumentation,
            @Nullable ClassFileTransformer preCheckClassFileTransformer) throws Exception {
        String version = Version.getVersion(MainEntryPoint.class);
        startupLogger.info("Glowroot version: {}", version);
        startupLogger.info("Java version: {}", StandardSystemProperty.JAVA_VERSION.value());
        glowrootAgentInit = createGlowrootAgentInit(directories, properties, instrumentation);
        glowrootAgentInit.init(directories.getPluginsDir(), directories.getConfDir(),
                directories.getSharedConfDir(), directories.getLogDir(), directories.getTmpDir(),
                directories.getGlowrootJarFile(), properties, instrumentation,
                preCheckClassFileTransformer, version);
    }

    @RequiresNonNull("startupLogger")
    private static GlowrootAgentInit createGlowrootAgentInit(Directories directories,
            Map<String, String> properties, @Nullable Instrumentation instrumentation)
            throws Exception {
        String collectorAddress = properties.get("glowroot.collector.address");
        if (collectorAddress != null) {
            collectorAddress = collectorAddress.trim();
        }
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
        if (Strings.isNullOrEmpty(collectorAddress)) {
            File embeddedCollectorJarFile = directories.getEmbeddedCollectorJarFile();
            if (embeddedCollectorJarFile != null && instrumentation != null) {
                instrumentation
                        .appendToSystemClassLoaderSearch(new JarFile(embeddedCollectorJarFile));
            }
            Class<?> factoryClass;
            try {
                factoryClass = Class.forName(
                        "org.glowroot.agent.embedded.init.EmbeddedGlowrootAgentInitFactory", true,
                        ClassLoader.getSystemClassLoader());
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

    private static ImmutableMap<String, String> getGlowrootProperties(File confDir,
            @Nullable File sharedConfDir) throws IOException {
        Map<String, String> properties = Maps.newHashMap();
        if (sharedConfDir == null) {
            addProperties(confDir, properties);
        } else {
            addProperties(sharedConfDir, properties);
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

    private static void addProperties(File dir, Map<String, String> properties)
            throws IOException {
        File propFile = new File(dir, "glowroot.properties");
        if (!propFile.exists()) {
            return;
        }
        // upgrade from 0.9.6 to 0.9.7
        PropertiesFiles.upgradeIfNeeded(propFile,
                ImmutableMap.of("agent.rollup=", "agent.rollup.id="));
        // upgrade from 0.9.13 to 0.9.14
        upgradeToCollectorAddressIfNeeded(propFile);
        // upgrade from 0.9.26 to 0.9.27
        addSchemeToCollectorAddressIfNeeded(propFile);
        // upgrade from 0.9.28 to 0.10.0
        prependAgentRollupToAgentIdIfNeeded(propFile);
        Properties props = PropertiesFiles.load(propFile);
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (value != null) {
                properties.put("glowroot." + key, value);
            }
        }
    }

    @RequiresNonNull("startupLogger")
    private static void logAgentDirsLockedException(File confDir, File lockFile) {
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
            startupLogger.error("Glowroot not started, directory in use by another jvm process: {}"
                    + " (unable to obtain lock on {})", confDir.getAbsolutePath(),
                    lockFile.getAbsolutePath());
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
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charsets.UTF_8));
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
        String lowerOsName = osName.toLowerCase(Locale.ENGLISH);
        if (lowerOsName.startsWith("linux")) {
            return "linux";
        } else if (lowerOsName.startsWith("windows")) {
            return "windows";
        } else if (lowerOsName.startsWith("macosx") || lowerOsName.startsWith("osx")) {
            return "linux";
        } else {
            return null;
        }
    }

    private static void upgradeToCollectorAddressIfNeeded(File propFile) throws IOException {
        List<String> lines = readPropertiesFile(propFile);
        List<String> newLines = upgradeToCollectorAddressIfNeeded(lines);
        if (!newLines.equals(lines)) {
            writePropertiesFile(propFile, newLines);
        }
    }

    @VisibleForTesting
    static List<String> upgradeToCollectorAddressIfNeeded(List<String> lines) {
        List<String> newLines = Lists.newArrayList();
        String host = null;
        String port = null;
        int indexForAddress = -1;
        for (String line : lines) {
            if (line.startsWith("collector.host=")) {
                host = line.substring("collector.host=".length());
                if (indexForAddress == -1) {
                    indexForAddress = newLines.size();
                }
            } else if (line.startsWith("collector.port=")) {
                port = line.substring("collector.port=".length());
                if (indexForAddress == -1) {
                    indexForAddress = newLines.size();
                }
            } else if (line.startsWith("collector.address=")) {
                return lines;
            } else {
                newLines.add(line);
            }
        }
        if (indexForAddress == -1) {
            return newLines;
        }
        if (host == null) {
            return newLines;
        }
        if (host.isEmpty()) {
            newLines.add(indexForAddress, "collector.address=");
            return newLines;
        }
        if (port == null || port.isEmpty()) {
            port = "8181";
        }
        newLines.add(indexForAddress, "collector.address=" + host + ":" + port);
        return newLines;
    }

    private static void addSchemeToCollectorAddressIfNeeded(File propFile) throws IOException {
        List<String> lines = readPropertiesFile(propFile);
        List<String> newLines = addSchemeToCollectorAddressIfNeeded(lines);
        if (!newLines.equals(lines)) {
            writePropertiesFile(propFile, newLines);
        }
    }

    private static List<String> addSchemeToCollectorAddressIfNeeded(List<String> lines) {
        List<String> newLines = Lists.newArrayList();
        for (String line : lines) {
            if (line.startsWith("collector.address=")) {
                String collectorAddress = line.substring("collector.address=".length());
                List<String> addrs = Lists.newArrayList();
                boolean modified = false;
                for (String addr : Splitter.on(',').trimResults().omitEmptyStrings()
                        .split(collectorAddress)) {
                    // need to check for "http\://" and "https\://" since those are allowed and
                    // interpreted by Properties.load() as "http://" and "https://"
                    if (addr.startsWith("http://") || addr.startsWith("https://")
                            || addr.startsWith("http\\://") || addr.startsWith("https\\://")) {
                        addrs.add(addr);
                    } else {
                        addrs.add("http://" + addr);
                        modified = true;
                    }
                }
                if (modified) {
                    newLines.add("collector.address=" + Joiner.on(',').join(addrs));
                } else {
                    newLines.add(line);
                }
            } else {
                newLines.add(line);
            }
        }
        return newLines;
    }

    private static void prependAgentRollupToAgentIdIfNeeded(File propFile) throws IOException {
        List<String> lines = readPropertiesFile(propFile);
        List<String> newLines = prependAgentRollupToAgentIdIfNeeded(lines);
        if (!newLines.equals(lines)) {
            writePropertiesFile(propFile, newLines);
        }
    }

    private static List<String> prependAgentRollupToAgentIdIfNeeded(List<String> lines) {
        List<String> newLines = Lists.newArrayList();
        String agentId = null;
        String agentRollupId = null;
        int agentIdLineIndex = -1;
        for (String line : lines) {
            if (line.startsWith("agent.id=")) {
                agentId = line.substring("agent.id=".length());
                agentIdLineIndex = newLines.size();
                newLines.add(line);
            } else if (line.startsWith("agent.rollup.id=")) {
                agentRollupId = line.substring("agent.rollup.id=".length());
            } else {
                newLines.add(line);
            }
        }
        if (agentIdLineIndex != -1 && !Strings.isNullOrEmpty(agentRollupId)) {
            newLines.set(agentIdLineIndex,
                    "agent.id=" + agentRollupId.replace("/", "::") + "::" + agentId);
        }
        return newLines;
    }

    private static List<String> readPropertiesFile(File propFile) throws IOException {
        // properties files must be ISO_8859_1
        return Files.readLines(propFile, Charsets.ISO_8859_1);
    }

    private static void writePropertiesFile(File propFile, List<String> newLines)
            throws FileNotFoundException {
        // properties files must be ISO_8859_1
        PrintWriter out = new PrintWriter(Files.newWriter(propFile, Charsets.ISO_8859_1));
        try {
            for (String newLine : newLines) {
                out.println(newLine);
            }
        } finally {
            out.close();
        }
    }

    @OnlyUsedByTests
    public static void start(Map<String, String> properties) throws Exception {
        String testDirPath = properties.get("glowroot.test.dir");
        checkNotNull(testDirPath);
        File testDir = new File(testDirPath);
        // init logger as early as possible
        initLogging(testDir, null, testDir, null);
        Directories directories = new Directories(testDir, false);
        start(directories, properties, null, null);
    }

    @OnlyUsedByTests
    public static @Nullable GlowrootAgentInit getGlowrootAgentInit() {
        return glowrootAgentInit;
    }
}
