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

import checkers.nullness.quals.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.Static;

/**
 * "There are some things that agents are allowed to do that simply should not be permitted"
 * 
 * -- http://mail.openjdk.java.net/pipermail/hotspot-dev/2012-March/005464.html
 * 
 * In particular (at least prior to parallel class loading in JDK 7) initializing other classes
 * inside of a ClassFileTransformer.transform() method occasionally leads to deadlocks. To avoid
 * initializing other classes inside of the transform() method, all classes referenced from
 * WeavingClassFileTransformer are pre-initialized (and all classes referenced from those classes,
 * etc).
 * 
 * Class loading is also a bad idea inside of JVM shutdown hooks, see
 * https://bugs.openjdk.java.net/browse/JDK-7142035
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
class PreInitializeClasses {

    private static final Logger logger = LoggerFactory.getLogger(PreInitializeClasses.class);

    private PreInitializeClasses() {}

    // null loader means the bootstrap class loader
    static void preInitializeClasses(@Nullable ClassLoader loader) {
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
        try {
            Class.forName(type, true, loader);
        } catch (ClassNotFoundException e) {
            logger.warn("class not found: {}", type);
        }
    }

    @VisibleForTesting
    static ImmutableList<String> usedTypes() {
        ImmutableList.Builder<String> types = ImmutableList.builder();
        types.addAll(getJacksonUsedTypes());
        types.addAll(getGuavaUsedTypes());
        types.addAll(getGlowrootUsedTypes());
        types.addAll(getH2UsedTypes());
        types.addAll(getAsmUsedTypes());
        return types.build();

    }

    private static List<String> getGuavaUsedTypes() {
        List<String> types = Lists.newArrayList();

        types.add("com.google.common.base.Ascii");
        types.add("com.google.common.base.CharMatcher");
        types.add("com.google.common.base.CharMatcher$1");
        types.add("com.google.common.base.CharMatcher$10");
        types.add("com.google.common.base.CharMatcher$13");
        types.add("com.google.common.base.CharMatcher$15");
        types.add("com.google.common.base.CharMatcher$2");
        types.add("com.google.common.base.CharMatcher$3");
        types.add("com.google.common.base.CharMatcher$4");
        types.add("com.google.common.base.CharMatcher$5");
        types.add("com.google.common.base.CharMatcher$6");
        types.add("com.google.common.base.CharMatcher$7");
        types.add("com.google.common.base.CharMatcher$8");
        types.add("com.google.common.base.CharMatcher$9");
        types.add("com.google.common.base.CharMatcher$FastMatcher");
        types.add("com.google.common.base.CharMatcher$NegatedFastMatcher");
        types.add("com.google.common.base.CharMatcher$NegatedMatcher");
        types.add("com.google.common.base.CharMatcher$Or");
        types.add("com.google.common.base.CharMatcher$RangesMatcher");
        types.add("com.google.common.base.Equivalence");
        types.add("com.google.common.base.Equivalence$Equals");
        types.add("com.google.common.base.Equivalence$Identity");
        types.add("com.google.common.base.Function");
        types.add("com.google.common.base.Joiner");
        types.add("com.google.common.base.Joiner$1");
        types.add("com.google.common.base.Joiner$MapJoiner");
        types.add("com.google.common.base.Objects");
        types.add("com.google.common.base.Objects$1");
        types.add("com.google.common.base.Objects$ToStringHelper");
        types.add("com.google.common.base.Objects$ToStringHelper$ValueHolder");
        types.add("com.google.common.base.Platform");
        types.add("com.google.common.base.Platform$1");
        types.add("com.google.common.base.Preconditions");
        types.add("com.google.common.base.Predicate");
        types.add("com.google.common.base.Predicates");
        types.add("com.google.common.base.Predicates$1");
        types.add("com.google.common.base.Predicates$IsEqualToPredicate");
        types.add("com.google.common.base.Predicates$ObjectPredicate");
        types.add("com.google.common.base.Predicates$ObjectPredicate$1");
        types.add("com.google.common.base.Predicates$ObjectPredicate$2");
        types.add("com.google.common.base.Predicates$ObjectPredicate$3");
        types.add("com.google.common.base.Predicates$ObjectPredicate$4");
        types.add("com.google.common.base.Stopwatch");
        types.add("com.google.common.base.Stopwatch$1");
        types.add("com.google.common.base.Strings");
        types.add("com.google.common.base.Supplier");
        types.add("com.google.common.base.Suppliers");
        types.add("com.google.common.base.Suppliers$SupplierOfInstance");
        types.add("com.google.common.base.Throwables");
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
        types.add("com.google.common.cache.Striped64$HashCode");
        types.add("com.google.common.cache.Striped64$ThreadHashCode");
        types.add("com.google.common.cache.Weigher");
        types.add("com.google.common.collect.AbstractIndexedListIterator");
        types.add("com.google.common.collect.AbstractMapEntry");
        types.add("com.google.common.collect.AbstractSequentialIterator");
        types.add("com.google.common.collect.BiMap");
        types.add("com.google.common.collect.ByFunctionOrdering");
        types.add("com.google.common.collect.CollectPreconditions");
        types.add("com.google.common.collect.Collections2");
        types.add("com.google.common.collect.EmptyImmutableBiMap");
        types.add("com.google.common.collect.EmptyImmutableSet");
        types.add("com.google.common.collect.FluentIterable");
        types.add("com.google.common.collect.Hashing");
        types.add("com.google.common.collect.ImmutableAsList");
        types.add("com.google.common.collect.ImmutableBiMap");
        types.add("com.google.common.collect.ImmutableCollection");
        types.add("com.google.common.collect.ImmutableCollection$ArrayBasedBuilder");
        types.add("com.google.common.collect.ImmutableCollection$Builder");
        types.add("com.google.common.collect.ImmutableEntry");
        types.add("com.google.common.collect.ImmutableList");
        types.add("com.google.common.collect.ImmutableList$1");
        types.add("com.google.common.collect.ImmutableList$Builder");
        types.add("com.google.common.collect.ImmutableList$ReverseImmutableList");
        types.add("com.google.common.collect.ImmutableList$SubList");
        types.add("com.google.common.collect.ImmutableMap");
        types.add("com.google.common.collect.ImmutableMap$Builder");
        types.add("com.google.common.collect.ImmutableMapEntry");
        types.add("com.google.common.collect.ImmutableMapEntry$TerminalEntry");
        types.add("com.google.common.collect.ImmutableMapEntrySet");
        types.add("com.google.common.collect.ImmutableMapKeySet");
        types.add("com.google.common.collect.ImmutableMapKeySet$1");
        types.add("com.google.common.collect.ImmutableMapValues");
        types.add("com.google.common.collect.ImmutableMapValues$1");
        types.add("com.google.common.collect.ImmutableSet");
        types.add("com.google.common.collect.Iterables");
        types.add("com.google.common.collect.Iterables$2");
        types.add("com.google.common.collect.Iterables$3");
        types.add("com.google.common.collect.Iterators");
        types.add("com.google.common.collect.Iterators$1");
        types.add("com.google.common.collect.Iterators$11");
        types.add("com.google.common.collect.Iterators$12");
        types.add("com.google.common.collect.Iterators$2");
        types.add("com.google.common.collect.Iterators$5");
        types.add("com.google.common.collect.Lists");
        types.add("com.google.common.collect.Lists$RandomAccessReverseList");
        types.add("com.google.common.collect.Lists$ReverseList");
        types.add("com.google.common.collect.Lists$ReverseList$1");
        types.add("com.google.common.collect.Maps");
        types.add("com.google.common.collect.Maps$1");
        types.add("com.google.common.collect.Multimap");
        types.add("com.google.common.collect.NaturalOrdering");
        types.add("com.google.common.collect.ObjectArrays");
        types.add("com.google.common.collect.Ordering");
        types.add("com.google.common.collect.Platform");
        types.add("com.google.common.collect.Queues");
        types.add("com.google.common.collect.RegularImmutableAsList");
        types.add("com.google.common.collect.RegularImmutableList");
        types.add("com.google.common.collect.RegularImmutableMap");
        types.add("com.google.common.collect.RegularImmutableMap$1");
        types.add("com.google.common.collect.RegularImmutableMap$EntrySet");
        types.add("com.google.common.collect.RegularImmutableMap$NonTerminalMapEntry");
        types.add("com.google.common.collect.ReverseNaturalOrdering");
        types.add("com.google.common.collect.ReverseOrdering");
        types.add("com.google.common.collect.SetMultimap");
        types.add("com.google.common.collect.Sets");
        types.add("com.google.common.collect.SingletonImmutableBiMap");
        types.add("com.google.common.collect.SingletonImmutableList");
        types.add("com.google.common.collect.SingletonImmutableSet");
        types.add("com.google.common.collect.TransformedIterator");
        types.add("com.google.common.collect.UnmodifiableIterator");
        types.add("com.google.common.collect.UnmodifiableListIterator");
        types.add("com.google.common.io.ByteSource");
        types.add("com.google.common.io.ByteStreams");
        types.add("com.google.common.io.ByteStreams$1");
        types.add("com.google.common.io.Closeables");
        types.add("com.google.common.io.Closer");
        types.add("com.google.common.io.Closer$LoggingSuppressor");
        types.add("com.google.common.io.Closer$SuppressingSuppressor");
        types.add("com.google.common.io.Closer$Suppressor");
        types.add("com.google.common.io.InputSupplier");
        types.add("com.google.common.io.LineProcessor");
        types.add("com.google.common.io.Resources");
        types.add("com.google.common.io.Resources$1");
        types.add("com.google.common.io.Resources$UrlByteSource");
        types.add("com.google.common.primitives.Ints");
        types.add("com.google.common.util.concurrent.AbstractFuture");
        types.add("com.google.common.util.concurrent.AbstractFuture$Sync");
        types.add("com.google.common.util.concurrent.AbstractListeningExecutorService");
        types.add("com.google.common.util.concurrent.AsyncFunction");
        types.add("com.google.common.util.concurrent.ExecutionError");
        types.add("com.google.common.util.concurrent.ExecutionList");
        types.add("com.google.common.util.concurrent.ExecutionList$RunnableExecutorPair");
        types.add("com.google.common.util.concurrent.Futures");
        types.add("com.google.common.util.concurrent.Futures$1");
        types.add("com.google.common.util.concurrent.Futures$3");
        types.add("com.google.common.util.concurrent.Futures$5");
        types.add("com.google.common.util.concurrent.Futures$ChainingListenableFuture");
        types.add("com.google.common.util.concurrent.Futures$ChainingListenableFuture$1");
        types.add("com.google.common.util.concurrent.Futures$ImmediateFailedFuture");
        types.add("com.google.common.util.concurrent.Futures$ImmediateFuture");
        types.add("com.google.common.util.concurrent.Futures$ImmediateSuccessfulFuture");
        types.add("com.google.common.util.concurrent.ListenableFuture");
        types.add("com.google.common.util.concurrent.ListenableFutureTask");
        types.add("com.google.common.util.concurrent.ListeningExecutorService");
        types.add("com.google.common.util.concurrent.MoreExecutors");
        types.add("com.google.common.util.concurrent.MoreExecutors$1");
        types.add("com.google.common.util.concurrent.MoreExecutors$SameThreadExecutorService");
        types.add("com.google.common.util.concurrent.SettableFuture");
        types.add("com.google.common.util.concurrent.UncheckedExecutionException");
        types.add("com.google.common.util.concurrent.Uninterruptibles");
        return types;
    }
    private static List<String> getJacksonUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("com.fasterxml.jackson.core.JsonFactory");
        types.add("com.fasterxml.jackson.core.JsonFactory$Feature");
        types.add("com.fasterxml.jackson.core.JsonGenerator$Feature");
        types.add("com.fasterxml.jackson.core.JsonParser$Feature");
        types.add("com.fasterxml.jackson.core.ObjectCodec");
        types.add("com.fasterxml.jackson.core.PrettyPrinter");
        types.add("com.fasterxml.jackson.core.SerializableString");
        types.add("com.fasterxml.jackson.core.TreeCodec");
        types.add("com.fasterxml.jackson.core.Versioned");
        types.add("com.fasterxml.jackson.core.io.SerializedString");
        types.add("com.fasterxml.jackson.core.sym.BytesToNameCanonicalizer");
        types.add("com.fasterxml.jackson.core.sym.BytesToNameCanonicalizer$Bucket");
        types.add("com.fasterxml.jackson.core.sym.BytesToNameCanonicalizer$TableInfo");
        types.add("com.fasterxml.jackson.core.sym.CharsToNameCanonicalizer");
        types.add("com.fasterxml.jackson.core.sym.CharsToNameCanonicalizer$Bucket");
        types.add("com.fasterxml.jackson.core.sym.Name");
        types.add("com.fasterxml.jackson.core.util.DefaultPrettyPrinter");
        types.add("com.fasterxml.jackson.core.util.DefaultPrettyPrinter$FixedSpaceIndenter");
        types.add("com.fasterxml.jackson.core.util.DefaultPrettyPrinter$Indenter");
        types.add("com.fasterxml.jackson.core.util.DefaultPrettyPrinter$Lf2SpacesIndenter");
        types.add("com.fasterxml.jackson.core.util.DefaultPrettyPrinter$NopIndenter");
        types.add("com.fasterxml.jackson.core.util.Instantiatable");
        return types;
    }

    private static List<String> getGlowrootUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.glowroot.api.ErrorMessage");
        types.add("org.glowroot.api.Message");
        types.add("org.glowroot.api.MessageSupplier");
        types.add("org.glowroot.api.MetricName");
        types.add("org.glowroot.api.MetricTimer");
        types.add("org.glowroot.api.weaving.BindMethodArg");
        types.add("org.glowroot.api.weaving.BindMethodArgArray");
        types.add("org.glowroot.api.weaving.BindMethodName");
        types.add("org.glowroot.api.weaving.BindOptionalReturn");
        types.add("org.glowroot.api.weaving.BindReceiver");
        types.add("org.glowroot.api.weaving.BindReturn");
        types.add("org.glowroot.api.weaving.BindThrowable");
        types.add("org.glowroot.api.weaving.BindTraveler");
        types.add("org.glowroot.api.weaving.IsEnabled");
        types.add("org.glowroot.api.weaving.MethodModifier");
        types.add("org.glowroot.api.weaving.OnAfter");
        types.add("org.glowroot.api.weaving.OnBefore");
        types.add("org.glowroot.api.weaving.OnReturn");
        types.add("org.glowroot.api.weaving.OnThrow");
        types.add("org.glowroot.api.weaving.Pointcut");
        types.add("org.glowroot.common.Nullness");
        types.add("org.glowroot.common.Reflections");
        types.add("org.glowroot.common.Reflections$ReflectiveException");
        types.add("org.glowroot.common.Reflections$ReflectiveTargetException");
        types.add("org.glowroot.common.ScheduledRunnable");
        types.add("org.glowroot.common.ScheduledRunnable$TerminateSubsequentExecutionsException");
        types.add("org.glowroot.local.store.CappedDatabase");
        types.add("org.glowroot.local.store.CappedDatabase$ShutdownHookThread");
        types.add("org.glowroot.local.store.CappedDatabaseOutputStream");
        types.add("org.glowroot.local.store.CappedDatabaseOutputStream$FsyncScheduledRunnable");
        types.add("org.glowroot.local.store.DataSource");
        types.add("org.glowroot.local.store.DataSource$1");
        types.add("org.glowroot.local.store.DataSource$ShutdownHookThread");
        types.add("org.glowroot.trace.MetricNameCache");
        types.add("org.glowroot.trace.MetricTimerServiceImpl");
        types.add("org.glowroot.trace.MetricTimerServiceImpl$NopMetricTimer");
        types.add("org.glowroot.trace.TraceRegistry");
        types.add("org.glowroot.trace.model.JvmInfo");
        types.add("org.glowroot.trace.model.MergedStackTree");
        types.add("org.glowroot.trace.model.Metric");
        types.add("org.glowroot.trace.model.MetricNameImpl");
        types.add("org.glowroot.trace.model.RootSpan");
        types.add("org.glowroot.trace.model.Span");
        types.add("org.glowroot.trace.model.Trace");
        types.add("org.glowroot.trace.model.TraceUniqueId");
        types.add("org.glowroot.weaving.Advice");
        types.add("org.glowroot.weaving.Advice$AdviceParameter");
        types.add("org.glowroot.weaving.Advice$ParameterKind");
        types.add("org.glowroot.weaving.AdviceFlowOuterHolder");
        types.add("org.glowroot.weaving.AdviceFlowOuterHolder$1");
        types.add("org.glowroot.weaving.AdviceFlowOuterHolder$AdviceFlowHolder");
        types.add("org.glowroot.weaving.AdviceMatcher");
        types.add("org.glowroot.weaving.MetricTimerService");
        types.add("org.glowroot.weaving.MixinMatcher");
        types.add("org.glowroot.weaving.MixinType");
        types.add("org.glowroot.weaving.ParsedMethod");
        types.add("org.glowroot.weaving.ParsedType");
        types.add("org.glowroot.weaving.ParsedType$Builder");
        types.add("org.glowroot.weaving.ParsedTypeCache");
        types.add("org.glowroot.weaving.ParsedTypeCache$1");
        types.add("org.glowroot.weaving.ParsedTypeCache$ParseContext");
        types.add("org.glowroot.weaving.ParsedTypeCache$ParsedTypeClassVisitor");
        types.add("org.glowroot.weaving.PreInitializeClasses");
        types.add("org.glowroot.weaving.TypeNames");
        types.add("org.glowroot.weaving.Weaver");
        types.add("org.glowroot.weaving.Weaver$ComputeFramesClassWriter");
        types.add("org.glowroot.weaving.Weaver$JSRInlinerClassVisitor");
        types.add("org.glowroot.weaving.WeavingClassFileTransformer");
        types.add("org.glowroot.weaving.WeavingClassFileTransformer$1");
        types.add("org.glowroot.weaving.WeavingClassVisitor");
        types.add("org.glowroot.weaving.WeavingClassVisitor$InitMixins");
        types.add("org.glowroot.weaving.WeavingMethodVisitor");
        types.add("org.glowroot.weaving.WeavingMethodVisitor$MarkerException");
        return types;
    }

    private static List<String> getH2UsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.h2.api.DatabaseEventListener");
        types.add("org.h2.command.CommandInterface");
        types.add("org.h2.command.CommandRemote");
        types.add("org.h2.command.dml.SetTypes");
        types.add("org.h2.compress.CompressDeflate");
        types.add("org.h2.compress.CompressLZF");
        types.add("org.h2.compress.CompressNo");
        types.add("org.h2.compress.Compressor");
        types.add("org.h2.constant.DbSettings");
        types.add("org.h2.constant.ErrorCode");
        types.add("org.h2.constant.SysProperties");
        types.add("org.h2.engine.ConnectionInfo");
        types.add("org.h2.engine.Constants");
        types.add("org.h2.engine.SessionFactory");
        types.add("org.h2.engine.SessionInterface");
        types.add("org.h2.engine.SessionRemote");
        types.add("org.h2.engine.SessionWithState");
        types.add("org.h2.engine.SettingsBase");
        types.add("org.h2.expression.ParameterInterface");
        types.add("org.h2.expression.ParameterRemote");
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
        types.add("org.h2.result.ResultColumn");
        types.add("org.h2.result.ResultInterface");
        types.add("org.h2.result.ResultRemote");
        types.add("org.h2.result.UpdatableRow");
        types.add("org.h2.security.AES");
        types.add("org.h2.security.BlockCipher");
        types.add("org.h2.security.CipherFactory");
        types.add("org.h2.security.Fog");
        types.add("org.h2.security.SHA256");
        types.add("org.h2.security.SecureFileStore");
        types.add("org.h2.security.XTEA");
        types.add("org.h2.store.Data");
        types.add("org.h2.store.DataHandler");
        types.add("org.h2.store.DataReader");
        types.add("org.h2.store.DataReader$FastEOFException");
        types.add("org.h2.store.FileStore");
        types.add("org.h2.store.FileStoreInputStream");
        types.add("org.h2.store.FileStoreOutputStream");
        types.add("org.h2.store.LobStorage");
        types.add("org.h2.store.LobStorage$CountingReaderInputStream");
        types.add("org.h2.store.LobStorage$LobInputStream");
        types.add("org.h2.store.LobStorage$RemoteInputStream");
        types.add("org.h2.store.fs.FileBase");
        types.add("org.h2.store.fs.FileDisk");
        types.add("org.h2.store.fs.FilePath");
        types.add("org.h2.store.fs.FilePathDisk");
        types.add("org.h2.store.fs.FilePathRec");
        types.add("org.h2.store.fs.FilePathWrapper");
        types.add("org.h2.store.fs.FileRec");
        types.add("org.h2.store.fs.FileUtils");
        types.add("org.h2.store.fs.Recorder");
        types.add("org.h2.tools.CompressTool");
        types.add("org.h2.tools.SimpleResultSet");
        types.add("org.h2.tools.SimpleResultSet$Column");
        types.add("org.h2.tools.SimpleResultSet$SimpleArray");
        types.add("org.h2.tools.SimpleRowSource");
        types.add("org.h2.util.BitField");
        types.add("org.h2.util.CloseWatcher");
        types.add("org.h2.util.DateTimeUtils");
        types.add("org.h2.util.IOUtils");
        types.add("org.h2.util.JdbcUtils");
        types.add("org.h2.util.MathUtils");
        types.add("org.h2.util.MathUtils$1");
        types.add("org.h2.util.NetUtils");
        types.add("org.h2.util.New");
        types.add("org.h2.util.SmallLRUCache");
        types.add("org.h2.util.SortedProperties");
        types.add("org.h2.util.StatementBuilder");
        types.add("org.h2.util.StringUtils");
        types.add("org.h2.util.Task");
        types.add("org.h2.util.TempFileDeleter");
        types.add("org.h2.util.Utils");
        types.add("org.h2.util.Utils$1");
        types.add("org.h2.value.CompareMode");
        types.add("org.h2.value.CompareModeDefault");
        types.add("org.h2.value.CompareModeIcu4J");
        types.add("org.h2.value.DataType");
        types.add("org.h2.value.Transfer");
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
        types.add("org.h2.value.ValueInt");
        types.add("org.h2.value.ValueJavaObject");
        types.add("org.h2.value.ValueLob");
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

    private static List<String> getAsmUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.objectweb.asm.AnnotationVisitor");
        types.add("org.objectweb.asm.AnnotationWriter");
        types.add("org.objectweb.asm.Attribute");
        types.add("org.objectweb.asm.ByteVector");
        types.add("org.objectweb.asm.ClassReader");
        types.add("org.objectweb.asm.ClassVisitor");
        types.add("org.objectweb.asm.ClassWriter");
        types.add("org.objectweb.asm.Context");
        types.add("org.objectweb.asm.Edge");
        types.add("org.objectweb.asm.FieldVisitor");
        types.add("org.objectweb.asm.FieldWriter");
        types.add("org.objectweb.asm.Frame");
        types.add("org.objectweb.asm.Handle");
        types.add("org.objectweb.asm.Handler");
        types.add("org.objectweb.asm.Item");
        types.add("org.objectweb.asm.Label");
        types.add("org.objectweb.asm.MethodVisitor");
        types.add("org.objectweb.asm.MethodWriter");
        types.add("org.objectweb.asm.Opcodes");
        types.add("org.objectweb.asm.Type");
        types.add("org.objectweb.asm.commons.AdviceAdapter");
        types.add("org.objectweb.asm.commons.GeneratorAdapter");
        types.add("org.objectweb.asm.commons.JSRInlinerAdapter");
        types.add("org.objectweb.asm.commons.JSRInlinerAdapter$Instantiation");
        types.add("org.objectweb.asm.commons.LocalVariablesSorter");
        types.add("org.objectweb.asm.commons.Method");
        types.add("org.objectweb.asm.commons.Remapper");
        types.add("org.objectweb.asm.commons.RemappingAnnotationAdapter");
        types.add("org.objectweb.asm.commons.RemappingMethodAdapter");
        types.add("org.objectweb.asm.commons.RemappingSignatureAdapter");
        types.add("org.objectweb.asm.commons.SimpleRemapper");
        types.add("org.objectweb.asm.signature.SignatureReader");
        types.add("org.objectweb.asm.signature.SignatureVisitor");
        types.add("org.objectweb.asm.signature.SignatureWriter");
        types.add("org.objectweb.asm.tree.AbstractInsnNode");
        types.add("org.objectweb.asm.tree.AnnotationNode");
        types.add("org.objectweb.asm.tree.ClassNode");
        types.add("org.objectweb.asm.tree.FieldInsnNode");
        types.add("org.objectweb.asm.tree.FieldNode");
        types.add("org.objectweb.asm.tree.FrameNode");
        types.add("org.objectweb.asm.tree.IincInsnNode");
        types.add("org.objectweb.asm.tree.InnerClassNode");
        types.add("org.objectweb.asm.tree.InsnList");
        types.add("org.objectweb.asm.tree.InsnNode");
        types.add("org.objectweb.asm.tree.IntInsnNode");
        types.add("org.objectweb.asm.tree.InvokeDynamicInsnNode");
        types.add("org.objectweb.asm.tree.JumpInsnNode");
        types.add("org.objectweb.asm.tree.LabelNode");
        types.add("org.objectweb.asm.tree.LdcInsnNode");
        types.add("org.objectweb.asm.tree.LineNumberNode");
        types.add("org.objectweb.asm.tree.LocalVariableNode");
        types.add("org.objectweb.asm.tree.LookupSwitchInsnNode");
        types.add("org.objectweb.asm.tree.MethodInsnNode");
        types.add("org.objectweb.asm.tree.MethodNode");
        types.add("org.objectweb.asm.tree.MethodNode$1");
        types.add("org.objectweb.asm.tree.MultiANewArrayInsnNode");
        types.add("org.objectweb.asm.tree.TableSwitchInsnNode");
        types.add("org.objectweb.asm.tree.TryCatchBlockNode");
        types.add("org.objectweb.asm.tree.TypeInsnNode");
        types.add("org.objectweb.asm.tree.VarInsnNode");
        types.add("org.objectweb.asm.tree.analysis.Analyzer");
        types.add("org.objectweb.asm.tree.analysis.AnalyzerException");
        types.add("org.objectweb.asm.tree.analysis.BasicInterpreter");
        types.add("org.objectweb.asm.tree.analysis.BasicValue");
        types.add("org.objectweb.asm.tree.analysis.BasicVerifier");
        types.add("org.objectweb.asm.tree.analysis.Frame");
        types.add("org.objectweb.asm.tree.analysis.Interpreter");
        types.add("org.objectweb.asm.tree.analysis.SimpleVerifier");
        types.add("org.objectweb.asm.tree.analysis.Subroutine");
        types.add("org.objectweb.asm.tree.analysis.Value");
        types.add("org.objectweb.asm.util.CheckAnnotationAdapter");
        types.add("org.objectweb.asm.util.CheckClassAdapter");
        types.add("org.objectweb.asm.util.CheckFieldAdapter");
        types.add("org.objectweb.asm.util.CheckMethodAdapter");
        types.add("org.objectweb.asm.util.CheckMethodAdapter$1");
        types.add("org.objectweb.asm.util.Printer");
        types.add("org.objectweb.asm.util.Textifiable");
        types.add("org.objectweb.asm.util.Textifier");
        types.add("org.objectweb.asm.util.TraceAnnotationVisitor");
        types.add("org.objectweb.asm.util.TraceMethodVisitor");
        types.add("org.objectweb.asm.util.TraceSignatureVisitor");
        return types;
    }

    @VisibleForTesting
    static ImmutableList<String> maybeUsedTypes() {
        ImmutableList.Builder<String> types = ImmutableList.builder();
        // these are special classes generated by javac (but not by the eclipse compiler) to handle
        // accessing the private constructor in an enclosed type
        // (see http://stackoverflow.com/questions/2883181)
        types.add("org.glowroot.common.Reflections$1");
        types.add("org.glowroot.trace.model.JvmInfo$1");
        types.add("org.glowroot.weaving.ParsedType$1");
        types.add("org.glowroot.weaving.Weaver$1");
        types.add("org.glowroot.weaving.WeavingMethodVisitor$1");
        // this is a special class generated by javac (but not by the eclipse compiler) to handle
        // enum switch statements
        // (see http://stackoverflow.com/questions/1834632/java-enum-and-additional-class-files)
        types.add("org.glowroot.weaving.AdviceMatcher$1");
        return types.build();
    }

    private static boolean exists(String type) {
        try {
            Class.forName(type);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
