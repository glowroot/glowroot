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
package org.glowroot.agent.init;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.weaving.ImportantClassNames;

// LIMIT DEPENDENCY USAGE IN THIS CLASS SO IT DOESN'T TRIGGER ANY CLASS LOADING ON ITS OWN
public class PreCheckLoadedClasses {

    // java.util.logging is shaded to org.glowroot.agent.jul
    private static final String SHADE_PROOF_JUL_LOGGER_CLASS_NAME =
            "_java.util.logging.Logger".substring(1);

    private static final Set<String> IMPORTANT_CLASS_NAMES;

    // only check for java.util.logging.Logger under test, since just want to make sure glowroot
    // doesn't accidentally initialize java.util.logging.Logger itself, but it's not a problem
    // for glowroot if someone else initializes it early (e.g. and weblogic does)
    private static final boolean CHECK_FOR_JUL_LOGGER =
            System.getProperty("glowroot.test.dir") != null && isShaded();

    static {
        List<String> importantClassNames = Arrays.asList(
                ImportantClassNames.JBOSS_WELD_HACK_CLASS_NAME.replace('/', '.'),
                ImportantClassNames.JBOSS_URL_HACK_CLASS_NAME.replace('/', '.'));
        IMPORTANT_CLASS_NAMES = new HashSet<String>(importantClassNames);
    }

    private PreCheckLoadedClasses() {}

    static boolean isImportantClass(String className, Class<?> clazz) {
        return IMPORTANT_CLASS_NAMES.contains(className)
                || isImportantRunnableOrCallable(className, clazz)
                || className.equals("java.net.HttpURLConnection")
                || CHECK_FOR_JUL_LOGGER && className.equals(SHADE_PROOF_JUL_LOGGER_CLASS_NAME);
    }

    private static boolean isImportantRunnableOrCallable(String className, Class<?> clazz) {
        return className.startsWith("java.util.concurrent.") && !clazz.isInterface()
                && (Runnable.class.isAssignableFrom(clazz)
                        || Callable.class.isAssignableFrom(clazz));
    }

    private static boolean isShaded() {
        try {
            Class.forName("org.glowroot.agent.shaded.org.slf4j.Logger");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static class PreCheckClassFileTransformer implements ClassFileTransformer {

        private static volatile @MonotonicNonNull Logger logger;

        private final Set<String> importantInternalNames;
        private final String shadeProofJulLoggerInternalName =
                SHADE_PROOF_JUL_LOGGER_CLASS_NAME.replace('.', '/');

        private final Map<String, Exception> importantClassLoadingPoints =
                new ConcurrentHashMap<String, Exception>();

        public PreCheckClassFileTransformer() {
            List<String> internalNames = new ArrayList<String>();
            for (String className : IMPORTANT_CLASS_NAMES) {
                internalNames.add(className.replace('.', '/'));
            }
            importantInternalNames = new HashSet<String>(internalNames);
        }

        public Map<String, Exception> getImportantClassLoadingPoints() {
            return importantClassLoadingPoints;
        }

        @Override
        public byte /*@Nullable*/ [] transform(@Nullable ClassLoader loader,
                @Nullable String className, @Nullable Class<?> classBeingRedefined,
                @Nullable ProtectionDomain protectionDomain, byte[] bytes) {
            try {
                if (className == null) {
                    return null;
                }
                if (importantInternalNames.contains(className)
                        || className.equals("java/net/HttpURLConnection")
                        || CHECK_FOR_JUL_LOGGER
                                && className.equals(shadeProofJulLoggerInternalName)) {
                    importantClassLoadingPoints.put(className.replace('/', '.'),
                            new Exception("location stack trace"));
                }
            } catch (Throwable t) {
                if (logger == null) {
                    t.printStackTrace();
                } else {
                    logger.error(t.getMessage(), t);
                }
            }
            return null;
        }

        public static void initLogger() {
            logger = LoggerFactory.getLogger(PreCheckLoadedClasses.class);
        }
    }
}
