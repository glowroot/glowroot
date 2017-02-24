/*
 * Copyright 2012-2017 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
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
            initialize(type, loader);
        }
        for (String type : maybeUsedTypes()) {
            if (exists(type)) {
                initialize(type, loader);
            }
        }
    }

    private static void initialize(String type, @Nullable ClassLoader loader) {
        if (type.equals("org.h2.value.ValueGeometry")) {
            // this type depends on an optional third party library and is not really used
            // (initializing the class throws an error due to the dependency)
            return;
        }
        try {
            Class.forName(type, true, loader);
        } catch (ClassNotFoundException e) {
            logger.warn("class not found: {}", type);
            // log exception at trace level
            logger.trace(e.getMessage(), e);
        }
    }

    @VisibleForTesting
    static List<String> usedTypes() {
        List<String> types = Lists.newArrayList();
        types.addAll(getGlowrootUsedTypes());
        types.addAll(getH2UsedTypes());
        return types;
    }

    private static List<String> getGlowrootUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.glowroot.agent.embedded.util.CappedDatabase");
        types.add("org.glowroot.agent.embedded.util.CappedDatabase$ShutdownHookThread");
        types.add("org.glowroot.agent.embedded.util.CappedDatabaseOutputStream");
        types.add("org.glowroot.agent.embedded.util.DataSource");
        types.add("org.glowroot.agent.embedded.util.DataSource$ShutdownHookThread");
        return types;
    }

    private static List<String> getH2UsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.h2.api.ErrorCode");
        types.add("org.h2.api.JavaObjectSerializer");
        types.add("org.h2.command.CommandInterface");
        types.add("org.h2.compress.CompressDeflate");
        types.add("org.h2.compress.CompressLZF");
        types.add("org.h2.compress.CompressNo");
        types.add("org.h2.compress.Compressor");
        types.add("org.h2.engine.Constants");
        types.add("org.h2.engine.SessionInterface");
        types.add("org.h2.engine.SysProperties");
        types.add("org.h2.expression.ParameterInterface");
        types.add("org.h2.jdbc.JdbcArray");
        types.add("org.h2.jdbc.JdbcBatchUpdateException");
        types.add("org.h2.jdbc.JdbcBlob");
        types.add("org.h2.jdbc.JdbcBlob$1");
        types.add("org.h2.jdbc.JdbcBlob$2");
        types.add("org.h2.jdbc.JdbcCallableStatement");
        types.add("org.h2.jdbc.JdbcClob");
        types.add("org.h2.jdbc.JdbcClob$1");
        types.add("org.h2.jdbc.JdbcClob$2");
        types.add("org.h2.jdbc.JdbcConnection");
        types.add("org.h2.jdbc.JdbcDatabaseMetaData");
        types.add("org.h2.jdbc.JdbcParameterMetaData");
        types.add("org.h2.jdbc.JdbcPreparedStatement");
        types.add("org.h2.jdbc.JdbcResultSet");
        types.add("org.h2.jdbc.JdbcResultSetMetaData");
        types.add("org.h2.jdbc.JdbcSQLException");
        types.add("org.h2.jdbc.JdbcSavepoint");
        types.add("org.h2.jdbc.JdbcStatement");
        types.add("org.h2.message.DbException");
        types.add("org.h2.message.Trace");
        types.add("org.h2.message.TraceObject");
        types.add("org.h2.message.TraceSystem");
        types.add("org.h2.message.TraceWriter");
        types.add("org.h2.mvstore.DataUtils");
        types.add("org.h2.result.ResultInterface");
        types.add("org.h2.result.UpdatableRow");
        types.add("org.h2.store.Data");
        types.add("org.h2.store.DataHandler");
        types.add("org.h2.store.FileStore");
        types.add("org.h2.store.FileStoreInputStream");
        types.add("org.h2.store.LobStorageInterface");
        types.add("org.h2.store.fs.FilePath");
        types.add("org.h2.store.fs.FileUtils");
        types.add("org.h2.tools.CompressTool");
        types.add("org.h2.tools.SimpleResultSet");
        types.add("org.h2.tools.SimpleResultSet$Column");
        types.add("org.h2.tools.SimpleResultSet$SimpleArray");
        types.add("org.h2.tools.SimpleRowSource");
        types.add("org.h2.util.BitField");
        types.add("org.h2.util.CloseWatcher");
        types.add("org.h2.util.DateTimeUtils");
        types.add("org.h2.util.IOUtils");
        types.add("org.h2.util.MathUtils");
        types.add("org.h2.util.New");
        types.add("org.h2.util.SortedProperties");
        types.add("org.h2.util.StatementBuilder");
        types.add("org.h2.util.StringUtils");
        types.add("org.h2.util.Task");
        types.add("org.h2.util.Utils");
        types.add("org.h2.util.Utils$1");
        types.add("org.h2.util.Utils$ClassFactory");
        types.add("org.h2.value.CompareMode");
        types.add("org.h2.value.DataType");
        types.add("org.h2.value.Value");
        types.add("org.h2.value.Value$ValueBlob");
        types.add("org.h2.value.Value$ValueClob");
        types.add("org.h2.value.ValueArray");
        types.add("org.h2.value.ValueBoolean");
        types.add("org.h2.value.ValueByte");
        types.add("org.h2.value.ValueBytes");
        types.add("org.h2.value.ValueDate");
        types.add("org.h2.value.ValueDecimal");
        types.add("org.h2.value.ValueDouble");
        types.add("org.h2.value.ValueFloat");
        types.add("org.h2.value.ValueGeometry");
        types.add("org.h2.value.ValueInt");
        types.add("org.h2.value.ValueJavaObject");
        types.add("org.h2.value.ValueJavaObject$NotSerialized");
        types.add("org.h2.value.ValueLobDb");
        types.add("org.h2.value.ValueLong");
        types.add("org.h2.value.ValueNull");
        types.add("org.h2.value.ValueResultSet");
        types.add("org.h2.value.ValueShort");
        types.add("org.h2.value.ValueString");
        types.add("org.h2.value.ValueStringFixed");
        types.add("org.h2.value.ValueStringIgnoreCase");
        types.add("org.h2.value.ValueTime");
        types.add("org.h2.value.ValueTimestamp");
        types.add("org.h2.value.ValueUuid");
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
        return types;
    }

    private static boolean exists(String type) {
        try {
            Class.forName(type);
            return true;
        } catch (ClassNotFoundException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            return false;
        }
    }
}
