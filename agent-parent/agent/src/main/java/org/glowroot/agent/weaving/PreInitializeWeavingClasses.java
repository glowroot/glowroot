/*
 * Copyright 2012-2016 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// "There are some things that agents are allowed to do that simply should not be permitted"
//
// -- http://mail.openjdk.java.net/pipermail/hotspot-dev/2012-March/005464.html
//
// in particular (at least prior to parallel class loading in JDK 7) initializing other classes
// inside of a ClassFileTransformer.transform() method occasionally leads to deadlocks
//
// to avoid initializing other classes inside of the transform() method, all classes referenced from
// WeavingClassFileTransformer are pre-initialized (and all classes referenced from those classes,
// etc)
public class PreInitializeWeavingClasses {

    private static final Logger logger = LoggerFactory.getLogger(PreInitializeWeavingClasses.class);

    private PreInitializeWeavingClasses() {}

    public static void preInitializeClasses() {
        ClassLoader loader = PreInitializeWeavingClasses.class.getClassLoader();
        for (String type : usedTypes()) {
            initialize(type, loader, true);
        }
        for (String type : maybeUsedTypes()) {
            initialize(type, loader, false);
        }
        for (String type : javaUsedTypes()) {
            // passing warnOnNotExists=false since ThreadLocalRandom only exists in jdk 1.7+
            initialize(type, loader, false);
        }
    }

    private static void initialize(String type, @Nullable ClassLoader loader,
            boolean warnOnNotExists) {
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
        types.addAll(getGuavaUsedTypes());
        types.add("com.google.protobuf.Internal$EnumLite");
        types.add("com.google.protobuf.Internal$EnumLiteMap");
        types.add("com.google.protobuf.ProtocolMessageEnum");
        types.addAll(getGlowrootUsedTypes());
        types.addAll(getAsmUsedTypes());
        return types;
    }

    private static List<String> getGuavaUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("com.google.common.base.Charsets");
        types.add("com.google.common.base.Function");
        types.add("com.google.common.base.Joiner");
        types.add("com.google.common.base.Joiner$1");
        types.add("com.google.common.base.Joiner$MapJoiner");
        types.add("com.google.common.base.MoreObjects");
        types.add("com.google.common.base.MoreObjects$1");
        types.add("com.google.common.base.MoreObjects$ToStringHelper");
        types.add("com.google.common.base.MoreObjects$ToStringHelper$ValueHolder");
        types.add("com.google.common.base.Objects");
        types.add("com.google.common.base.Platform");
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
        types.add("com.google.common.base.StandardSystemProperty");
        types.add("com.google.common.base.Supplier");
        types.add("com.google.common.base.Throwables");
        types.add("com.google.common.base.Ticker");
        types.add("com.google.common.base.Ticker$1");
        types.add("com.google.common.collect.AbstractIndexedListIterator");
        types.add("com.google.common.collect.AbstractMapEntry");
        types.add("com.google.common.collect.BiMap");
        types.add("com.google.common.collect.ByFunctionOrdering");
        types.add("com.google.common.collect.CollectPreconditions");
        types.add("com.google.common.collect.Collections2");
        types.add("com.google.common.collect.ComparatorOrdering");
        types.add("com.google.common.collect.DescendingImmutableSortedSet");
        types.add("com.google.common.collect.Hashing");
        types.add("com.google.common.collect.ImmutableAsList");
        types.add("com.google.common.collect.ImmutableBiMap");
        types.add("com.google.common.collect.ImmutableCollection");
        types.add("com.google.common.collect.ImmutableCollection$ArrayBasedBuilder");
        types.add("com.google.common.collect.ImmutableCollection$Builder");
        types.add("com.google.common.collect.ImmutableEntry");
        types.add("com.google.common.collect.ImmutableEnumMap");
        types.add("com.google.common.collect.ImmutableEnumSet");
        types.add("com.google.common.collect.ImmutableList");
        types.add("com.google.common.collect.ImmutableList$1");
        types.add("com.google.common.collect.ImmutableList$Builder");
        types.add("com.google.common.collect.ImmutableList$ReverseImmutableList");
        types.add("com.google.common.collect.ImmutableList$SubList");
        types.add("com.google.common.collect.ImmutableMap");
        types.add("com.google.common.collect.ImmutableMap$1");
        types.add("com.google.common.collect.ImmutableMap$Builder");
        types.add("com.google.common.collect.ImmutableMap$IteratorBasedImmutableMap");
        types.add("com.google.common.collect.ImmutableMap$IteratorBasedImmutableMap$1EntrySetImpl");
        types.add("com.google.common.collect.ImmutableMapEntry");
        types.add("com.google.common.collect.ImmutableMapEntry$NonTerminalImmutableMapEntry");
        types.add("com.google.common.collect.ImmutableMapEntrySet");
        types.add("com.google.common.collect.ImmutableMapEntrySet$RegularEntrySet");
        types.add("com.google.common.collect.ImmutableMapKeySet");
        types.add("com.google.common.collect.ImmutableMapValues");
        types.add("com.google.common.collect.ImmutableMapValues$1");
        types.add("com.google.common.collect.ImmutableMapValues$2");
        types.add("com.google.common.collect.ImmutableSet");
        types.add("com.google.common.collect.ImmutableSet$Builder");
        types.add("com.google.common.collect.ImmutableSet$Indexed");
        types.add("com.google.common.collect.ImmutableSet$Indexed$1");
        types.add("com.google.common.collect.ImmutableSortedAsList");
        types.add("com.google.common.collect.ImmutableSortedMap");
        types.add("com.google.common.collect.ImmutableSortedMap$1EntrySet");
        types.add("com.google.common.collect.ImmutableSortedMap$1EntrySet$1");
        types.add("com.google.common.collect.ImmutableSortedMapFauxverideShim");
        types.add("com.google.common.collect.ImmutableSortedSet");
        types.add("com.google.common.collect.ImmutableSortedSetFauxverideShim");
        types.add("com.google.common.collect.Iterables");
        types.add("com.google.common.collect.Iterators");
        types.add("com.google.common.collect.Iterators$1");
        types.add("com.google.common.collect.Iterators$11");
        types.add("com.google.common.collect.Iterators$12");
        types.add("com.google.common.collect.Iterators$2");
        types.add("com.google.common.collect.Iterators$3");
        types.add("com.google.common.collect.Iterators$PeekingImpl");
        types.add("com.google.common.collect.Lists");
        types.add("com.google.common.collect.Lists$RandomAccessReverseList");
        types.add("com.google.common.collect.Lists$ReverseList");
        types.add("com.google.common.collect.Lists$ReverseList$1");
        types.add("com.google.common.collect.Maps");
        types.add("com.google.common.collect.Maps$1");
        types.add("com.google.common.collect.Maps$5");
        types.add("com.google.common.collect.Maps$6");
        types.add("com.google.common.collect.Maps$EntryFunction");
        types.add("com.google.common.collect.Maps$EntryFunction$1");
        types.add("com.google.common.collect.Maps$EntryFunction$2");
        types.add("com.google.common.collect.Multiset");
        types.add("com.google.common.collect.NaturalOrdering");
        types.add("com.google.common.collect.ObjectArrays");
        types.add("com.google.common.collect.Ordering");
        types.add("com.google.common.collect.PeekingIterator");
        types.add("com.google.common.collect.Platform");
        types.add("com.google.common.collect.RegularImmutableAsList");
        types.add("com.google.common.collect.RegularImmutableBiMap");
        types.add("com.google.common.collect.RegularImmutableBiMap$1");
        types.add("com.google.common.collect.RegularImmutableBiMap$Inverse");
        types.add("com.google.common.collect.RegularImmutableBiMap$Inverse$InverseEntrySet");
        types.add("com.google.common.collect.RegularImmutableBiMap$Inverse$InverseEntrySet$1");
        types.add("com.google.common.collect.RegularImmutableList");
        types.add("com.google.common.collect.RegularImmutableMap");
        types.add("com.google.common.collect.RegularImmutableSet");
        types.add("com.google.common.collect.RegularImmutableSortedSet");
        types.add("com.google.common.collect.ReverseNaturalOrdering");
        types.add("com.google.common.collect.ReverseOrdering");
        types.add("com.google.common.collect.Sets");
        types.add("com.google.common.collect.SingletonImmutableBiMap");
        types.add("com.google.common.collect.SingletonImmutableList");
        types.add("com.google.common.collect.SingletonImmutableSet");
        types.add("com.google.common.collect.SortedIterable");
        types.add("com.google.common.collect.SortedIterables");
        types.add("com.google.common.collect.SortedLists");
        types.add("com.google.common.collect.SortedLists$1");
        types.add("com.google.common.collect.SortedLists$KeyAbsentBehavior");
        types.add("com.google.common.collect.SortedLists$KeyAbsentBehavior$1");
        types.add("com.google.common.collect.SortedLists$KeyAbsentBehavior$2");
        types.add("com.google.common.collect.SortedLists$KeyAbsentBehavior$3");
        types.add("com.google.common.collect.SortedLists$KeyPresentBehavior");
        types.add("com.google.common.collect.SortedLists$KeyPresentBehavior$1");
        types.add("com.google.common.collect.SortedLists$KeyPresentBehavior$2");
        types.add("com.google.common.collect.SortedLists$KeyPresentBehavior$3");
        types.add("com.google.common.collect.SortedLists$KeyPresentBehavior$4");
        types.add("com.google.common.collect.SortedLists$KeyPresentBehavior$5");
        types.add("com.google.common.collect.TransformedIterator");
        types.add("com.google.common.collect.TreeTraverser");
        types.add("com.google.common.collect.UnmodifiableIterator");
        types.add("com.google.common.collect.UnmodifiableListIterator");
        types.add("com.google.common.io.ByteSink");
        types.add("com.google.common.io.ByteSource");
        types.add("com.google.common.io.ByteStreams");
        types.add("com.google.common.io.ByteStreams$1");
        types.add("com.google.common.io.Closeables");
        types.add("com.google.common.io.Closer");
        types.add("com.google.common.io.Closer$LoggingSuppressor");
        types.add("com.google.common.io.Closer$SuppressingSuppressor");
        types.add("com.google.common.io.Closer$Suppressor");
        types.add("com.google.common.io.Files");
        types.add("com.google.common.io.Files$1");
        types.add("com.google.common.io.Files$2");
        types.add("com.google.common.io.Files$FileByteSink");
        types.add("com.google.common.io.FileWriteMode");
        types.add("com.google.common.io.LineProcessor");
        types.add("com.google.common.io.Resources");
        types.add("com.google.common.io.Resources$1");
        types.add("com.google.common.io.Resources$UrlByteSource");
        types.add("com.google.common.primitives.Booleans");
        types.add("com.google.common.primitives.Bytes");
        types.add("com.google.common.primitives.Ints");
        return types;
    }

    private static List<String> getGlowrootUsedTypes() {
        List<String> types = Lists.newArrayList();
        types.add("org.glowroot.agent.advicegen.AdviceGenerator");
        types.add("org.glowroot.agent.config.ImmutableInstrumentationConfig");
        types.add("org.glowroot.agent.config.ImmutableInstrumentationConfig$Builder");
        types.add("org.glowroot.agent.config.ImmutableInstrumentationConfig$InitShim");
        types.add("org.glowroot.agent.config.InstrumentationConfig");
        types.add("org.glowroot.agent.impl.TransactionRegistry");
        types.add("org.glowroot.agent.impl.TransactionRegistry$TransactionRegistryHolder");
        types.add("org.glowroot.agent.impl.TransactionServiceImpl");
        types.add("org.glowroot.agent.impl.TransactionServiceImpl$TransactionServiceHolder");
        types.add("org.glowroot.agent.impl.WeavingTimerServiceImpl");
        types.add("org.glowroot.agent.impl.WeavingTimerServiceImpl$2");
        types.add("org.glowroot.agent.impl.WeavingTimerServiceImpl$NopWeavingTimer");
        types.add("org.glowroot.agent.model.CommonTimerImpl");
        types.add("org.glowroot.agent.model.NestedTimerMap");
        types.add("org.glowroot.agent.model.OptionalThreadContextImpl");
        types.add("org.glowroot.agent.model.ThreadContextImpl");
        types.add("org.glowroot.agent.model.ThreadContextPlus");
        types.add("org.glowroot.agent.model.TimerImpl");
        types.add("org.glowroot.agent.model.TimerNameImpl");
        types.add("org.glowroot.agent.plugin.api.OptionalThreadContext");
        types.add("org.glowroot.agent.plugin.api.ThreadContext");
        types.add("org.glowroot.agent.plugin.api.Timer");
        types.add("org.glowroot.agent.plugin.api.TimerName");
        types.add("org.glowroot.agent.plugin.api.config.ConfigListener");
        types.add("org.glowroot.agent.plugin.api.util.FastThreadLocal");
        types.add("org.glowroot.agent.plugin.api.util.FastThreadLocal$Holder");
        types.add("org.glowroot.agent.plugin.api.weaving.BindClassMeta");
        types.add("org.glowroot.agent.plugin.api.weaving.BindMethodMeta");
        types.add("org.glowroot.agent.plugin.api.weaving.BindMethodName");
        types.add("org.glowroot.agent.plugin.api.weaving.BindOptionalReturn");
        types.add("org.glowroot.agent.plugin.api.weaving.BindParameter");
        types.add("org.glowroot.agent.plugin.api.weaving.BindParameterArray");
        types.add("org.glowroot.agent.plugin.api.weaving.BindReceiver");
        types.add("org.glowroot.agent.plugin.api.weaving.BindReturn");
        types.add("org.glowroot.agent.plugin.api.weaving.BindThrowable");
        types.add("org.glowroot.agent.plugin.api.weaving.BindTraveler");
        types.add("org.glowroot.agent.plugin.api.weaving.IsEnabled");
        types.add("org.glowroot.agent.plugin.api.weaving.MethodModifier");
        types.add("org.glowroot.agent.plugin.api.weaving.OnAfter");
        types.add("org.glowroot.agent.plugin.api.weaving.OnBefore");
        types.add("org.glowroot.agent.plugin.api.weaving.OnReturn");
        types.add("org.glowroot.agent.plugin.api.weaving.OnThrow");
        types.add("org.glowroot.agent.plugin.api.weaving.Pointcut");
        types.add("org.glowroot.agent.plugin.api.weaving.Shim");
        types.add("org.glowroot.agent.util.Reflections");
        types.add("org.glowroot.agent.util.Tickers");
        types.add("org.glowroot.agent.util.Tickers$DummyTicker");
        types.add("org.glowroot.agent.weaving.Advice");
        types.add("org.glowroot.agent.weaving.Advice$AdviceOrdering");
        types.add("org.glowroot.agent.weaving.Advice$AdviceParameter");
        types.add("org.glowroot.agent.weaving.AdviceAdapter");
        types.add("org.glowroot.agent.weaving.AdviceBuilder");
        types.add("org.glowroot.agent.weaving.AdviceBuilder$1");
        types.add("org.glowroot.agent.weaving.AdviceBuilder$AdviceConstructionException");
        types.add("org.glowroot.agent.weaving.AdviceMatcher");
        types.add("org.glowroot.agent.weaving.AnalyzedClass");
        types.add("org.glowroot.agent.weaving.AnalyzedMethod");
        types.add("org.glowroot.agent.weaving.AnalyzedWorld");
        types.add("org.glowroot.agent.weaving.AnalyzedWorld$ParseContext");
        types.add("org.glowroot.agent.weaving.BootstrapMetaHolders");
        types.add("org.glowroot.agent.weaving.BootstrapMetaHolders$ClassMetaHolder");
        types.add("org.glowroot.agent.weaving.BootstrapMetaHolders$MethodMetaHolder");
        types.add("org.glowroot.agent.weaving.BridgeMethodClassVisitor");
        types.add("org.glowroot.agent.weaving.BridgeMethodClassVisitor$BridgeMethodVisitor");
        types.add("org.glowroot.agent.weaving.ClassAnalyzer");
        types.add("org.glowroot.agent.weaving.ClassAnalyzer$AnalyzedMethodKey");
        types.add("org.glowroot.agent.weaving.ClassLoaders");
        types.add("org.glowroot.agent.weaving.ClassLoaders$LazyDefinedClass");
        types.add("org.glowroot.agent.weaving.ClassNames");
        types.add("org.glowroot.agent.weaving.ExtraBootResourceFinder");
        types.add("org.glowroot.agent.weaving.FelixOsgiHackClassVisitor");
        types.add("org.glowroot.agent.weaving.FelixOsgiHackClassVisitor"
                + "$FelixOsgiHackMethodVisitor");
        types.add("org.glowroot.agent.weaving.GeneratedBytecodeUtil");
        types.add("org.glowroot.agent.weaving.ImmutableAdvice");
        types.add("org.glowroot.agent.weaving.ImmutableAdvice$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableAdvice$InitShim");
        types.add("org.glowroot.agent.weaving.ImmutableAdviceMatcher");
        types.add("org.glowroot.agent.weaving.ImmutableAdviceParameter");
        types.add("org.glowroot.agent.weaving.ImmutableAdviceParameter$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedClass");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedClass$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedMethod");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedMethod$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedMethodKey");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedMethodKey$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableCatchHandler");
        types.add("org.glowroot.agent.weaving.ImmutableLazyDefinedClass");
        types.add("org.glowroot.agent.weaving.ImmutableLazyDefinedClass$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableMethodMetaGroup");
        types.add("org.glowroot.agent.weaving.ImmutableMethodMetaGroup$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableParseContext");
        types.add("org.glowroot.agent.weaving.ImmutableThinClass");
        types.add("org.glowroot.agent.weaving.ImmutableThinClass$Builder");
        types.add("org.glowroot.agent.weaving.ImmutableThinMethod");
        types.add("org.glowroot.agent.weaving.ImmutableThinMethod$Builder");
        types.add("org.glowroot.agent.weaving.InstrumentationSeekerClassVisitor");
        types.add("org.glowroot.agent.weaving.InstrumentationSeekerClassVisitor"
                + "$InstrumentAnnotationMethodVisitor");
        types.add("org.glowroot.agent.weaving.InstrumentationSeekerClassVisitor"
                + "$TimerAnnotationVisitor");
        types.add("org.glowroot.agent.weaving.InstrumentationSeekerClassVisitor"
                + "$TraceEntryAnnotationVisitor");
        types.add("org.glowroot.agent.weaving.InstrumentationSeekerClassVisitor"
                + "$TransactionAnnotationVisitor");
        types.add("org.glowroot.agent.weaving.MixinType");
        types.add("org.glowroot.agent.weaving.ParameterKind");
        types.add("org.glowroot.agent.weaving.ShimType");
        types.add("org.glowroot.agent.weaving.ThinClassVisitor");
        types.add("org.glowroot.agent.weaving.ThinClassVisitor$AnnotationCaptureMethodVisitor");
        types.add("org.glowroot.agent.weaving.ThinClassVisitor$ThinClass");
        types.add("org.glowroot.agent.weaving.ThinClassVisitor$ThinMethod");
        types.add("org.glowroot.agent.weaving.Weaver");
        types.add("org.glowroot.agent.weaving.WeaverImpl");
        types.add("org.glowroot.agent.weaving.WeaverImpl$ComputeFramesClassWriter");
        types.add("org.glowroot.agent.weaving.WeaverImpl$JSRInlinerClassVisitor");
        types.add("org.glowroot.agent.weaving.WeavingClassFileTransformer");
        types.add("org.glowroot.agent.weaving.WeavingClassVisitor");
        types.add("org.glowroot.agent.weaving.WeavingClassVisitor$InitMixins");
        types.add("org.glowroot.agent.weaving.WeavingClassVisitor$MethodMetaGroup");
        types.add("org.glowroot.agent.weaving.WeavingMethodVisitor");
        types.add("org.glowroot.agent.weaving.WeavingMethodVisitor$CatchHandler");
        types.add("org.glowroot.agent.weaving.WeavingTimerService");
        types.add("org.glowroot.agent.weaving.WeavingTimerService$WeavingTimer");
        types.add("org.glowroot.common.util.Patterns");
        types.add("org.glowroot.wire.api.model.AgentConfigOuterClass$AgentConfig"
                + "$InstrumentationConfig$CaptureKind");
        types.add("org.glowroot.wire.api.model.AgentConfigOuterClass$AgentConfig"
                + "$InstrumentationConfig$CaptureKind$1");
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
        types.add("org.objectweb.asm.TypePath");
        types.add("org.objectweb.asm.TypeReference");
        types.add("org.objectweb.asm.commons.AdviceAdapter");
        types.add("org.objectweb.asm.commons.AnnotationRemapper");
        types.add("org.objectweb.asm.commons.GeneratorAdapter");
        types.add("org.objectweb.asm.commons.JSRInlinerAdapter");
        types.add("org.objectweb.asm.commons.JSRInlinerAdapter$Instantiation");
        types.add("org.objectweb.asm.commons.LocalVariablesSorter");
        types.add("org.objectweb.asm.commons.Method");
        types.add("org.objectweb.asm.commons.MethodRemapper");
        types.add("org.objectweb.asm.commons.Remapper");
        types.add("org.objectweb.asm.commons.SignatureRemapper");
        types.add("org.objectweb.asm.commons.SimpleRemapper");
        types.add("org.objectweb.asm.signature.SignatureReader");
        types.add("org.objectweb.asm.signature.SignatureVisitor");
        types.add("org.objectweb.asm.signature.SignatureWriter");
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
        types.add("org.objectweb.asm.tree.LocalVariableAnnotationNode");
        types.add("org.objectweb.asm.tree.LocalVariableNode");
        types.add("org.objectweb.asm.tree.LookupSwitchInsnNode");
        types.add("org.objectweb.asm.tree.MethodInsnNode");
        types.add("org.objectweb.asm.tree.MethodNode");
        types.add("org.objectweb.asm.tree.MethodNode$1");
        types.add("org.objectweb.asm.tree.MultiANewArrayInsnNode");
        types.add("org.objectweb.asm.tree.ParameterNode");
        types.add("org.objectweb.asm.tree.TableSwitchInsnNode");
        types.add("org.objectweb.asm.tree.TryCatchBlockNode");
        types.add("org.objectweb.asm.tree.TypeAnnotationNode");
        types.add("org.objectweb.asm.tree.TypeInsnNode");
        types.add("org.objectweb.asm.tree.VarInsnNode");
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
    static List<String> maybeUsedTypes() {
        List<String> types = Lists.newArrayList();
        // these are special classes generated by javac (but not by the eclipse compiler) to handle
        // accessing the private constructor in an enclosed type
        // (see http://stackoverflow.com/questions/2883181)
        types.add("org.glowroot.agent.config.ImmutableInstrumentationConfig$1");
        types.add("org.glowroot.agent.model.NestedTimerMap$1");
        types.add("org.glowroot.agent.util.Tickers$1");
        types.add("org.glowroot.agent.weaving.Advice$1");
        types.add("org.glowroot.agent.weaving.AnalyzedClass$1");
        types.add("org.glowroot.agent.weaving.AnalyzedMethod$1");
        types.add("org.glowroot.agent.weaving.AnalyzedMethodKey$1");
        types.add("org.glowroot.agent.weaving.BootstrapMetaHolders$1");
        types.add("org.glowroot.agent.weaving.BridgeMethodClassVisitor$1");
        types.add("org.glowroot.agent.weaving.FelixOsgiHackClassVisitor$1");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedClass$1");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedMethod$1");
        types.add("org.glowroot.agent.weaving.ImmutableAnalyzedMethodKey$1");
        types.add("org.glowroot.agent.weaving.ImmutableAdvice$1");
        types.add("org.glowroot.agent.weaving.ImmutableAdviceParameter$1");
        types.add("org.glowroot.agent.weaving.ImmutableLazyDefinedClass$1");
        types.add("org.glowroot.agent.weaving.ImmutableMethodMetaGroup$1");
        types.add("org.glowroot.agent.weaving.ImmutableThinClass$1");
        types.add("org.glowroot.agent.weaving.ImmutableThinMethod$1");
        types.add("org.glowroot.agent.weaving.InstrumentationSeekerClassVisitor$1");
        types.add("org.glowroot.agent.weaving.MethodMetaGroup$1");
        types.add("org.glowroot.agent.weaving.ThinClassVisitor$1");
        types.add("org.glowroot.agent.weaving.WeaverImpl$1");
        types.add("org.glowroot.agent.weaving.WeavingClassVisitor$1");
        // this is a special class generated by javac (but not by the eclipse compiler) to handle
        // enum switch statements
        // (see http://stackoverflow.com/questions/1834632/java-enum-and-additional-class-files)
        types.add("org.glowroot.agent.weaving.AdviceMatcher$1");
        types.add("org.glowroot.agent.weaving.WeavingMethodVisitor$1");
        // used when agent is shaded
        types.add("org.glowroot.agent.jul.Logger");
        types.add("org.glowroot.agent.jul.Level");
        return types;
    }

    // for the most part, adding used java types is not needed and will just slow down startup
    // exceptions can be added here
    private static List<String> javaUsedTypes() {
        List<String> types = Lists.newArrayList();
        // pre-initialize ThreadLocalRandom to avoid this error that occurred once during
        // integration tests (ClassLoaderLeakTest):
        //
        // java.lang.ClassCircularityError: sun/nio/ch/Interruptible
        //
        // java.lang.Class.getDeclaredFields0(Native Method)[na:1.8.0_20]
        // java.lang.Class.privateGetDeclaredFields(Class.java:2570)[na:1.8.0_20]
        // java.lang.Class.getDeclaredField(Class.java:2055)[na:1.8.0_20]
        // java.util.concurrent.ThreadLocalRandom.<clinit>(ThreadLocalRandom.java:1092)~[na:1.8.0_20]
        // java.util.concurrent.ConcurrentHashMap.fullAddCount(ConcurrentHashMap.java:2526)~[na:1.8.0_20]
        // java.util.concurrent.ConcurrentHashMap.addCount(ConcurrentHashMap.java:2266)~[na:1.8.0_20]
        // java.util.concurrent.ConcurrentHashMap.putVal(ConcurrentHashMap.java:1070)~[na:1.8.0_20]
        // java.util.concurrent.ConcurrentHashMap.put(ConcurrentHashMap.java:1006)~[na:1.8.0_20]
        // org.glowroot.weaving.AnalyzedWorld.add(AnalyzedWorld.java:156)~[na:0.5-SNAPSHOT]
        // org.glowroot.weaving.AnalyzingClassVisitor.visitEndReturningAnalyzedClass(AnalyzingClassVisitor.java:160)~[na:0.5-SNAPSHOT]
        // org.glowroot.weaving.WeavingClassVisitor.visitEnd(WeavingClassVisitor.java:229)~[na:0.5-SNAPSHOT]
        // org.glowroot.shaded.objectweb.asm.ClassVisitor.visitEnd(Unknown Source)~[na:0.5-SNAPSHOT]
        // org.glowroot.shaded.objectweb.asm.ClassReader.accept(Unknown Source)~[na:0.5-SNAPSHOT]
        // org.glowroot.shaded.objectweb.asm.ClassReader.accept(Unknown Source)~[na:0.5-SNAPSHOT]
        // org.glowroot.weaving.Weaver.weaveInternal(Weaver.java:115)[na:0.5-SNAPSHOT]
        // org.glowroot.weaving.Weaver.weave$glowroot$timer$glowroot$weaving$0(Weaver.java:88)[na:0.5-SNAPSHOT]
        // org.glowroot.weaving.Weaver.weave(Weaver.java:78)[na:0.5-SNAPSHOT]
        // org.glowroot.weaving.WeavingClassFileTransformer.transformInternal(WeavingClassFileTransformer.java:113)[na:0.5-SNAPSHOT]
        // org.glowroot.weaving.WeavingClassFileTransformer.transform(WeavingClassFileTransformer.java:76)[na:0.5-SNAPSHOT]
        // sun.instrument.TransformerManager.transform(TransformerManager.java:188)[na:1.8.0_20]
        // sun.instrument.InstrumentationImpl.transform(InstrumentationImpl.java:428)[na:1.8.0_20]
        // java.lang.Class.getDeclaredFields0(Native Method)[na:1.8.0_20]
        // java.lang.Class.privateGetDeclaredFields(Class.java:2570)[na:1.8.0_20]
        // java.lang.Class.getDeclaredField(Class.java:2055)[na:1.8.0_20]
        // java.util.concurrent.locks.LockSupport.<clinit>(LockSupport.java:404)[na:1.8.0_20]
        // java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await(AbstractQueuedSynchronizer.java:2039)[na:1.8.0_20]
        // java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(ScheduledThreadPoolExecutor.java:1088)[na:1.8.0_20]
        // java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(ScheduledThreadPoolExecutor.java:809)[na:1.8.0_20]
        // java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1067)[na:1.8.0_20]
        // java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1127)[na:1.8.0_20]
        // java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:617)[na:1.8.0_20]
        // java.lang.Thread.run(Thread.java:745)[na:1.8.0_20]
        types.add("java.util.concurrent.ThreadLocalRandom");
        return types;
    }
}
