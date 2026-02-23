/*
 * Copyright 2012-2023 the original author or authors.
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
package org.glowroot.agent.embedded.init;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Class loading is also a bad idea inside of JVM shutdown hooks, see
// https://bugs.openjdk.java.net/browse/JDK-7142035
class PreInitializeStorageShutdownClasses {

    private static final Logger logger =
            LoggerFactory.getLogger(PreInitializeStorageShutdownClasses.class);

    private PreInitializeStorageShutdownClasses() {}

    static void preInitializeClasses() {
        ClassLoader loader = PreInitializeStorageShutdownClasses.class.getClassLoader();
        for (String type : usedTypes()) {
            initialize(type, loader, true);
        }
        for (String type : maybeUsedTypes()) {
            initialize(type, loader, false);
        }
        for (String type : javaUsedTypes()) {
            initialize(type, loader, true);
        }
    }

    private static void initialize(String type, @Nullable ClassLoader loader,
            boolean warnOnNotExists) {
        if (type.equals("org.h2.value.ValueGeometry")) {
            // this type depends on an optional third party library and is not really used
            // (initializing the class throws an error due to the dependency)
            return;
        }
        try {
            Class.forName(type, true, loader);
        } catch (ClassNotFoundException e) {
            if (warnOnNotExists) {
                logger.warn("class not found: {}", type);
            }
            // log exception at trace level
            logger.trace(e.getMessage(), e);
        }
    }

    @VisibleForTesting
    static List<String> usedTypes() {
        List<String> types = Lists.newArrayList();
        types.addAll(getGlowrootUsedTypes());
        types.addAll(getH2UsedTypes());
        types.addAll(getGuavaUsedTypes());
        types.addAll(javaUsedTypes());
        return types;
    }

    private static List<String> getGlowrootUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.glowroot.agent.embedded.util.CappedDatabase");
        types.add("org.glowroot.agent.embedded.util.CappedDatabase$ShutdownHookThread");
        types.add("org.glowroot.agent.embedded.util.CappedDatabaseOutputStream");
        types.add("org.glowroot.agent.embedded.util.CappedDatabaseOutputStream$FsyncRunnable");
        types.add("org.glowroot.agent.embedded.util.DataSource");
        types.add("org.glowroot.agent.embedded.util.DataSource$ShutdownHookThread");
        types.add("org.glowroot.agent.util.JavaVersion");
        types.add("org.glowroot.common.util.Cancellable");
        types.add("org.glowroot.common.util.ScheduledRunnable");
        types.add("org.glowroot.common.util.ScheduledRunnable"
                + "$TerminateSubsequentExecutionsException");
        return types;
    }

    private static List<String> getH2UsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.h2.api.ErrorCode");
        types.add("org.h2.command.CommandInterface");
        types.add("org.h2.engine.CastDataProvider");
        types.add("org.h2.engine.Session");
        types.add("org.h2.jdbc.JdbcConnection");
        types.add("org.h2.jdbc.JdbcException");
        types.add("org.h2.jdbc.JdbcSQLDataException");
        types.add("org.h2.jdbc.JdbcSQLException");
        types.add("org.h2.jdbc.JdbcSQLFeatureNotSupportedException");
        types.add("org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException");
        types.add("org.h2.jdbc.JdbcSQLInvalidAuthorizationSpecException");
        types.add("org.h2.jdbc.JdbcSQLNonTransientConnectionException");
        types.add("org.h2.jdbc.JdbcSQLNonTransientException");
        types.add("org.h2.jdbc.JdbcSQLSyntaxErrorException");
        types.add("org.h2.jdbc.JdbcSQLTimeoutException");
        types.add("org.h2.jdbc.JdbcSQLTransactionRollbackException");
        types.add("org.h2.jdbc.JdbcSQLTransientException");
        types.add("org.h2.message.DbException");
        types.add("org.h2.message.Trace");
        types.add("org.h2.message.TraceObject");
        types.add("org.h2.message.TraceWriter");
        types.add("org.h2.mvstore.DataUtils");
        types.add("org.h2.result.ResultWithGeneratedKeys");
        types.add("org.h2.util.CloseWatcher");
        types.add("org.h2.util.IOUtils");
        types.add("org.h2.util.SortedProperties");
        types.add("org.h2.util.StringUtils");
        types.add("org.h2.util.Utils");
        return types;
    }

    private static List<String> getGuavaUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("com.google.common.base.StandardSystemProperty");
        types.add("com.google.common.base.Ticker");
        types.add("com.google.common.base.Ticker$1");
        return types;
    }

    @VisibleForTesting
    static List<String> maybeUsedTypes() {
        List<String> types = Lists.newArrayList();
        // these are special classes generated by javac (but not by the eclipse compiler) to handle
        // accessing the private constructor in an enclosed type
        // (see http://stackoverflow.com/questions/2883181)
        types.add("org.glowroot.common.Reflections$1");
        types.add("org.glowroot.core.weaving.AnalyzedClass$1");
        types.add("org.glowroot.core.weaving.Weaver$1");
        types.add("org.glowroot.core.weaving.WeavingMethodVisitor$1");
        // this is a special class generated by javac (but not by the eclipse compiler) to handle
        // enum switch statements
        // (see http://stackoverflow.com/questions/1834632/java-enum-and-additional-class-files)
        types.add("org.glowroot.core.weaving.AdviceMatcher$1");
        // used by Java 6-8, but not Java 9
        types.add("java.sql.Driver");
        // cannot rely on java anonymous inner class (e.g. when running on IBM J9 JVM)
        types.add("java.sql.DriverManager$1");
        types.add("java.sql.DriverManager$2");
        types.add("java.sql.SQLException$1");
        // used by H2 on older JVMs that have SecurityManager, but not on Java 25+ where
        // SecurityManager was removed
        types.add("java.sql.SQLPermission");
        return types;
    }

    // for the most part, adding used java types is not needed and will just slow down startup
    // exceptions can be added here
    private static List<String> javaUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("java.sql.Connection");
        types.add("java.sql.DriverManager");
        types.add("java.sql.SQLDataException");
        types.add("java.sql.SQLException");
        types.add("java.sql.SQLFeatureNotSupportedException");
        types.add("java.sql.SQLIntegrityConstraintViolationException");
        types.add("java.sql.SQLInvalidAuthorizationSpecException");
        types.add("java.sql.SQLNonTransientConnectionException");
        types.add("java.sql.SQLNonTransientException");
        types.add("java.sql.SQLSyntaxErrorException");
        types.add("java.sql.SQLTimeoutException");
        types.add("java.sql.SQLTransactionRollbackException");
        types.add("java.sql.SQLTransientException");
        types.add("java.sql.SQLWarning");
        types.add("java.sql.Statement");
        types.add("java.sql.Wrapper");
        return types;
    }
}
