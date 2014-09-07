/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.weaving;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Static;

/**
 * Class loading is also a bad idea inside of JVM shutdown hooks, see
 * https://bugs.openjdk.java.net/browse/JDK-7142035
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class PreInitializeStorageShutdownClasses {

    private static final Logger logger =
            LoggerFactory.getLogger(PreInitializeStorageShutdownClasses.class);

    private PreInitializeStorageShutdownClasses() {}

    public static void preInitializeClasses() {
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
            // log stack trace at debug level
            logger.debug(e.getMessage(), e);
        }
    }

    @VisibleForTesting
    static List<String> usedTypes() {
        List<String> types = Lists.newArrayList();
        types.addAll(getGuavaUsedTypes());
        types.addAll(getGlowrootUsedTypes());
        types.addAll(getH2UsedTypes());
        return types;

    }

    private static List<String> getGuavaUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("com.google.common.base.Ascii");
        types.add("com.google.common.base.Equivalence");
        types.add("com.google.common.base.Equivalence$Equals");
        types.add("com.google.common.base.Equivalence$Identity");
        types.add("com.google.common.base.Function");
        types.add("com.google.common.base.MoreObjects");
        types.add("com.google.common.base.MoreObjects$1");
        types.add("com.google.common.base.MoreObjects$ToStringHelper");
        types.add("com.google.common.base.MoreObjects$ToStringHelper$ValueHolder");
        types.add("com.google.common.base.Objects");
        types.add("com.google.common.base.Platform");
        types.add("com.google.common.base.Preconditions");
        types.add("com.google.common.base.Stopwatch");
        types.add("com.google.common.base.Stopwatch$1");
        types.add("com.google.common.base.Supplier");
        types.add("com.google.common.base.Suppliers");
        types.add("com.google.common.base.Suppliers$SupplierOfInstance");
        types.add("com.google.common.base.Ticker");
        types.add("com.google.common.base.Ticker$1");
        types.add("com.google.common.cache.AbstractCache$SimpleStatsCounter");
        types.add("com.google.common.cache.AbstractCache$StatsCounter");
        types.add("com.google.common.cache.Cache");
        types.add("com.google.common.cache.CacheBuilder");
        types.add("com.google.common.cache.CacheBuilder$1");
        types.add("com.google.common.cache.CacheBuilder$2");
        types.add("com.google.common.cache.CacheBuilder$3");
        types.add("com.google.common.cache.CacheBuilder$NullListener");
        types.add("com.google.common.cache.CacheBuilder$OneWeigher");
        types.add("com.google.common.cache.CacheLoader");
        types.add("com.google.common.cache.CacheLoader$InvalidCacheLoadException");
        types.add("com.google.common.cache.CacheStats");
        types.add("com.google.common.cache.LoadingCache");
        types.add("com.google.common.cache.LocalCache");
        types.add("com.google.common.cache.LocalCache$1");
        types.add("com.google.common.cache.LocalCache$2");
        types.add("com.google.common.cache.LocalCache$AbstractCacheSet");
        types.add("com.google.common.cache.LocalCache$AbstractReferenceEntry");
        types.add("com.google.common.cache.LocalCache$AccessQueue");
        types.add("com.google.common.cache.LocalCache$AccessQueue$1");
        types.add("com.google.common.cache.LocalCache$AccessQueue$2");
        types.add("com.google.common.cache.LocalCache$EntryFactory");
        types.add("com.google.common.cache.LocalCache$EntryFactory$1");
        types.add("com.google.common.cache.LocalCache$EntryFactory$2");
        types.add("com.google.common.cache.LocalCache$EntryFactory$3");
        types.add("com.google.common.cache.LocalCache$EntryFactory$4");
        types.add("com.google.common.cache.LocalCache$EntryFactory$5");
        types.add("com.google.common.cache.LocalCache$EntryFactory$6");
        types.add("com.google.common.cache.LocalCache$EntryFactory$7");
        types.add("com.google.common.cache.LocalCache$EntryFactory$8");
        types.add("com.google.common.cache.LocalCache$EntryIterator");
        types.add("com.google.common.cache.LocalCache$EntrySet");
        types.add("com.google.common.cache.LocalCache$HashIterator");
        types.add("com.google.common.cache.LocalCache$KeyIterator");
        types.add("com.google.common.cache.LocalCache$KeySet");
        types.add("com.google.common.cache.LocalCache$LoadingValueReference");
        types.add("com.google.common.cache.LocalCache$LoadingValueReference$1");
        types.add("com.google.common.cache.LocalCache$LocalLoadingCache");
        types.add("com.google.common.cache.LocalCache$LocalManualCache");
        types.add("com.google.common.cache.LocalCache$NullEntry");
        types.add("com.google.common.cache.LocalCache$ReferenceEntry");
        types.add("com.google.common.cache.LocalCache$Segment");
        types.add("com.google.common.cache.LocalCache$Segment$1");
        types.add("com.google.common.cache.LocalCache$SoftValueReference");
        types.add("com.google.common.cache.LocalCache$Strength");
        types.add("com.google.common.cache.LocalCache$Strength$1");
        types.add("com.google.common.cache.LocalCache$Strength$2");
        types.add("com.google.common.cache.LocalCache$Strength$3");
        types.add("com.google.common.cache.LocalCache$StrongAccessEntry");
        types.add("com.google.common.cache.LocalCache$StrongAccessWriteEntry");
        types.add("com.google.common.cache.LocalCache$StrongEntry");
        types.add("com.google.common.cache.LocalCache$StrongValueReference");
        types.add("com.google.common.cache.LocalCache$StrongWriteEntry");
        types.add("com.google.common.cache.LocalCache$ValueIterator");
        types.add("com.google.common.cache.LocalCache$ValueReference");
        types.add("com.google.common.cache.LocalCache$Values");
        types.add("com.google.common.cache.LocalCache$WeakAccessEntry");
        types.add("com.google.common.cache.LocalCache$WeakAccessWriteEntry");
        types.add("com.google.common.cache.LocalCache$WeakEntry");
        types.add("com.google.common.cache.LocalCache$WeakValueReference");
        types.add("com.google.common.cache.LocalCache$WeakWriteEntry");
        types.add("com.google.common.cache.LocalCache$WeightedSoftValueReference");
        types.add("com.google.common.cache.LocalCache$WeightedStrongValueReference");
        types.add("com.google.common.cache.LocalCache$WeightedWeakValueReference");
        types.add("com.google.common.cache.LocalCache$WriteQueue");
        types.add("com.google.common.cache.LocalCache$WriteQueue$1");
        types.add("com.google.common.cache.LocalCache$WriteQueue$2");
        types.add("com.google.common.cache.LocalCache$WriteThroughEntry");
        types.add("com.google.common.cache.LongAddable");
        types.add("com.google.common.cache.LongAddables");
        types.add("com.google.common.cache.LongAddables$1");
        types.add("com.google.common.cache.LongAddables$2");
        types.add("com.google.common.cache.LongAddables$PureJavaLongAddable");
        types.add("com.google.common.cache.LongAdder");
        types.add("com.google.common.cache.RemovalCause");
        types.add("com.google.common.cache.RemovalCause$1");
        types.add("com.google.common.cache.RemovalCause$2");
        types.add("com.google.common.cache.RemovalCause$3");
        types.add("com.google.common.cache.RemovalCause$4");
        types.add("com.google.common.cache.RemovalCause$5");
        types.add("com.google.common.cache.RemovalListener");
        types.add("com.google.common.cache.RemovalNotification");
        types.add("com.google.common.cache.Striped64");
        types.add("com.google.common.cache.Striped64$1");
        types.add("com.google.common.cache.Striped64$Cell");
        types.add("com.google.common.cache.Weigher");
        types.add("com.google.common.collect.AbstractSequentialIterator");
        types.add("com.google.common.collect.ByFunctionOrdering");
        types.add("com.google.common.collect.CollectPreconditions");
        types.add("com.google.common.collect.EmptyImmutableSet");
        types.add("com.google.common.collect.ImmutableCollection");
        types.add("com.google.common.collect.ImmutableSet");
        types.add("com.google.common.collect.Iterators");
        types.add("com.google.common.collect.Iterators$1");
        types.add("com.google.common.collect.Iterators$2");
        types.add("com.google.common.collect.NaturalOrdering");
        types.add("com.google.common.collect.ObjectArrays");
        types.add("com.google.common.collect.Ordering");
        types.add("com.google.common.collect.Platform");
        types.add("com.google.common.collect.ReverseNaturalOrdering");
        types.add("com.google.common.collect.ReverseOrdering");
        types.add("com.google.common.collect.Sets");
        types.add("com.google.common.collect.UnmodifiableIterator");
        types.add("com.google.common.collect.UnmodifiableListIterator");
        types.add("com.google.common.primitives.Ints");
        types.add("com.google.common.util.concurrent.AbstractFuture");
        types.add("com.google.common.util.concurrent.AbstractFuture$Sync");
        types.add("com.google.common.util.concurrent.AsyncFunction");
        types.add("com.google.common.util.concurrent.ExecutionError");
        types.add("com.google.common.util.concurrent.ExecutionList");
        types.add("com.google.common.util.concurrent.ExecutionList$RunnableExecutorPair");
        types.add("com.google.common.util.concurrent.Futures");
        types.add("com.google.common.util.concurrent.Futures$1");
        types.add("com.google.common.util.concurrent.Futures$1$1");
        types.add("com.google.common.util.concurrent.Futures$2");
        types.add("com.google.common.util.concurrent.Futures$4");
        types.add("com.google.common.util.concurrent.Futures$7");
        types.add("com.google.common.util.concurrent.Futures$ChainingListenableFuture");
        types.add("com.google.common.util.concurrent.Futures$ChainingListenableFuture$1");
        types.add("com.google.common.util.concurrent.Futures$ImmediateFailedFuture");
        types.add("com.google.common.util.concurrent.Futures$ImmediateFuture");
        types.add("com.google.common.util.concurrent.Futures$ImmediateSuccessfulFuture");
        types.add("com.google.common.util.concurrent.ListenableFuture");
        types.add("com.google.common.util.concurrent.MoreExecutors");
        types.add("com.google.common.util.concurrent.MoreExecutors$DirectExecutor");
        types.add("com.google.common.util.concurrent.SettableFuture");
        types.add("com.google.common.util.concurrent.UncheckedExecutionException");
        types.add("com.google.common.util.concurrent.Uninterruptibles");
        return types;
    }

    private static List<String> getGlowrootUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.glowroot.common.ScheduledRunnable");
        types.add("org.glowroot.common.ScheduledRunnable$TerminateSubsequentExecutionsException");
        types.add("org.glowroot.common.Ticker");
        types.add("org.glowroot.common.Ticker$1");
        types.add("org.glowroot.local.store.CappedDatabase");
        types.add("org.glowroot.local.store.CappedDatabase$ShutdownHookThread");
        types.add("org.glowroot.local.store.CappedDatabaseOutputStream");
        types.add("org.glowroot.local.store.CappedDatabaseOutputStream$FsyncRunnable");
        types.add("org.glowroot.local.store.DataSource");
        types.add("org.glowroot.local.store.DataSource$1");
        types.add("org.glowroot.local.store.DataSource$DataSourceLockedException");
        types.add("org.glowroot.local.store.DataSource$ShutdownHookThread");
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
        types.add("org.glowroot.trace.model.TraceGcInfo$1");
        types.add("org.glowroot.weaving.AnalyzedClass$1");
        types.add("org.glowroot.weaving.Weaver$1");
        types.add("org.glowroot.weaving.WeavingMethodVisitor$1");
        // this is a special class generated by javac (but not by the eclipse compiler) to handle
        // enum switch statements
        // (see http://stackoverflow.com/questions/1834632/java-enum-and-additional-class-files)
        types.add("org.glowroot.weaving.AdviceMatcher$1");
        return types;
    }

    private static boolean exists(String type) {
        try {
            Class.forName(type);
            return true;
        } catch (ClassNotFoundException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return false;
        }
    }
}
