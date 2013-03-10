/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.weaving;

import io.informant.util.Static;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

/**
 * "There are some things that agents are allowed to do that simply should not be permitted"
 * 
 * -- http://mail.openjdk.java.net/pipermail/hotspot-dev/2012-March/005464.html
 * 
 * In particular (at least prior to parallel class loading in JDK 7) initializing other classes
 * inside of a ClassFileTransformer.transform() method occasionally leads to deadlocks. To avoid
 * initializing other classes inside of the transform() method, all classes referenced from
 * InformantClassFileTransformer are pre-initialized (and all classes referenced from those classes,
 * etc).
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
            //
            logger.debug("class not found: {}", type);
        }
    }

    // comparing this list to the types that are kept by proguard:
    //
    // proguard keeps com.google.common.cache.AbstractCache, -whyareyoukeeping says it's because
    // io.informant.shaded.google.common.cache.AbstractCache "is extended by"
    // io.informant.shaded.google.common.cache.AbstractCache$StatsCounter (which is used)
    // but StatsCounter is an interface (and AbstractCache doesn't implement this interface), so
    // it's not clear why proguard is keeping it (TODO investigate further)
    // in any case, running integration-tests with -verbose:class confirms that AbstractCache is
    // never loaded, so it seems this class is not really used
    //
    // proguard keeps ch.qos.logback.classic.gaffer.GafferConfigurator, but this class implements
    // groovy.lang.GroovyObject which is not available on the classpath (so this class is not really
    // used)
    //
    // proguard does not keep a few interfaces (below), but that's because proguard strips these
    // (otherwise) unused interfaces off of used types
    // * org.slf4j.spi.LoggerFactoryBinder
    // * ch.qos.logback.core.spi.FilterAttachable
    // * ch.qos.logback.classic.jmx.JMXConfiguratorMBean
    //
    @VisibleForTesting
    static ImmutableList<String> usedTypes() {
        ImmutableList.Builder<String> types = ImmutableList.builder();
        types.add("ch.qos.logback.classic.BasicConfigurator");
        types.add("ch.qos.logback.classic.Level");
        types.add("ch.qos.logback.classic.Logger");
        types.add("ch.qos.logback.classic.LoggerContext");
        types.add("ch.qos.logback.classic.PatternLayout");
        types.add("ch.qos.logback.classic.boolex.JaninoEventEvaluator");
        types.add("ch.qos.logback.classic.encoder.PatternLayoutEncoder");
        types.add("ch.qos.logback.classic.gaffer.GafferUtil");
        types.add("ch.qos.logback.classic.jmx.JMXConfigurator");
        // JMXConfiguratorMBean interface stripped by proguard, see method comment
        if (exists("ch.qos.logback.classic.jmx.JMXConfiguratorMBean")) {
            types.add("ch.qos.logback.classic.jmx.JMXConfiguratorMBean");
        }
        types.add("ch.qos.logback.classic.jmx.MBeanUtil");
        types.add("ch.qos.logback.classic.joran.JoranConfigurator");
        types.add("ch.qos.logback.classic.joran.action.ConfigurationAction");
        types.add("ch.qos.logback.classic.joran.action.ConsolePluginAction");
        types.add("ch.qos.logback.classic.joran.action.ContextNameAction");
        types.add("ch.qos.logback.classic.joran.action.EvaluatorAction");
        types.add("ch.qos.logback.classic.joran.action.InsertFromJNDIAction");
        types.add("ch.qos.logback.classic.joran.action.JMXConfiguratorAction");
        types.add("ch.qos.logback.classic.joran.action.LevelAction");
        types.add("ch.qos.logback.classic.joran.action.LoggerAction");
        types.add("ch.qos.logback.classic.joran.action.LoggerContextListenerAction");
        types.add("ch.qos.logback.classic.joran.action.RootLoggerAction");
        types.add("ch.qos.logback.classic.net.LoggingEventPreSerializationTransformer");
        types.add("ch.qos.logback.classic.net.SocketAppender");
        types.add("ch.qos.logback.classic.pattern.Abbreviator");
        types.add("ch.qos.logback.classic.pattern.CallerDataConverter");
        types.add("ch.qos.logback.classic.pattern.ClassNameOnlyAbbreviator");
        types.add("ch.qos.logback.classic.pattern.ClassOfCallerConverter");
        types.add("ch.qos.logback.classic.pattern.ClassicConverter");
        types.add("ch.qos.logback.classic.pattern.ContextNameConverter");
        types.add("ch.qos.logback.classic.pattern.DateConverter");
        types.add("ch.qos.logback.classic.pattern.EnsureExceptionHandling");
        types.add("ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter");
        types.add("ch.qos.logback.classic.pattern.FileOfCallerConverter");
        types.add("ch.qos.logback.classic.pattern.LevelConverter");
        types.add("ch.qos.logback.classic.pattern.LineOfCallerConverter");
        types.add("ch.qos.logback.classic.pattern.LineSeparatorConverter");
        types.add("ch.qos.logback.classic.pattern.LoggerConverter");
        types.add("ch.qos.logback.classic.pattern.MDCConverter");
        types.add("ch.qos.logback.classic.pattern.MarkerConverter");
        types.add("ch.qos.logback.classic.pattern.MessageConverter");
        types.add("ch.qos.logback.classic.pattern.MethodOfCallerConverter");
        types.add("ch.qos.logback.classic.pattern.NamedConverter");
        types.add("ch.qos.logback.classic.pattern.NopThrowableInformationConverter");
        types.add("ch.qos.logback.classic.pattern.PropertyConverter");
        types.add("ch.qos.logback.classic.pattern.RelativeTimeConverter");
        types.add("ch.qos.logback.classic.pattern.RootCauseFirstThrowableProxyConverter");
        types.add("ch.qos.logback.classic.pattern.TargetLengthBasedClassNameAbbreviator");
        types.add("ch.qos.logback.classic.pattern.ThreadConverter");
        types.add("ch.qos.logback.classic.pattern.ThrowableHandlingConverter");
        types.add("ch.qos.logback.classic.pattern.ThrowableProxyConverter");
        types.add("ch.qos.logback.classic.pattern.color.HighlightingCompositeConverter");
        types.add("ch.qos.logback.classic.selector.ContextJNDISelector");
        types.add("ch.qos.logback.classic.selector.ContextSelector");
        types.add("ch.qos.logback.classic.selector.DefaultContextSelector");
        types.add("ch.qos.logback.classic.sift.AppenderFactory");
        types.add("ch.qos.logback.classic.sift.SiftAction");
        types.add("ch.qos.logback.classic.sift.SiftingAppender");
        types.add("ch.qos.logback.classic.sift.SiftingJoranConfigurator");
        types.add("ch.qos.logback.classic.spi.CallerData");
        types.add("ch.qos.logback.classic.spi.ClassPackagingData");
        types.add("ch.qos.logback.classic.spi.ILoggingEvent");
        types.add("ch.qos.logback.classic.spi.IThrowableProxy");
        types.add("ch.qos.logback.classic.spi.LoggerContextListener");
        types.add("ch.qos.logback.classic.spi.LoggerContextVO");
        types.add("ch.qos.logback.classic.spi.LoggerRemoteView");
        types.add("ch.qos.logback.classic.spi.LoggingEvent");
        types.add("ch.qos.logback.classic.spi.LoggingEventVO");
        types.add("ch.qos.logback.classic.spi.PackagingDataCalculator");
        types.add("ch.qos.logback.classic.spi.PlatformInfo");
        types.add("ch.qos.logback.classic.spi.STEUtil");
        types.add("ch.qos.logback.classic.spi.StackTraceElementProxy");
        types.add("ch.qos.logback.classic.spi.ThrowableProxy");
        types.add("ch.qos.logback.classic.spi.ThrowableProxyUtil");
        types.add("ch.qos.logback.classic.spi.ThrowableProxyVO");
        types.add("ch.qos.logback.classic.spi.TurboFilterList");
        types.add("ch.qos.logback.classic.turbo.ReconfigureOnChangeFilter");
        types.add("ch.qos.logback.classic.turbo.ReconfigureOnChangeFilter$ReconfiguringThread");
        types.add("ch.qos.logback.classic.turbo.TurboFilter");
        types.add("ch.qos.logback.classic.util.ContextInitializer");
        types.add("ch.qos.logback.classic.util.ContextSelectorStaticBinder");
        types.add("ch.qos.logback.classic.util.DefaultNestedComponentRules");
        types.add("ch.qos.logback.classic.util.EnvUtil");
        types.add("ch.qos.logback.classic.util.JNDIUtil");
        types.add("ch.qos.logback.classic.util.LogbackMDCAdapter");
        types.add("ch.qos.logback.classic.util.LoggerNameUtil");
        types.add("ch.qos.logback.classic.util.StatusListenerConfigHelper");
        types.add("ch.qos.logback.core.Appender");
        types.add("ch.qos.logback.core.AppenderBase");
        types.add("ch.qos.logback.core.BasicStatusManager");
        types.add("ch.qos.logback.core.ConsoleAppender");
        types.add("ch.qos.logback.core.Context");
        types.add("ch.qos.logback.core.ContextBase");
        types.add("ch.qos.logback.core.CoreConstants");
        types.add("ch.qos.logback.core.Layout");
        types.add("ch.qos.logback.core.LayoutBase");
        types.add("ch.qos.logback.core.LogbackException");
        types.add("ch.qos.logback.core.OutputStreamAppender");
        types.add("ch.qos.logback.core.UnsynchronizedAppenderBase");
        types.add("ch.qos.logback.core.boolex.EvaluationException");
        types.add("ch.qos.logback.core.boolex.EventEvaluator");
        types.add("ch.qos.logback.core.boolex.EventEvaluatorBase");
        types.add("ch.qos.logback.core.boolex.JaninoEventEvaluatorBase");
        types.add("ch.qos.logback.core.boolex.Matcher");
        types.add("ch.qos.logback.core.encoder.Encoder");
        types.add("ch.qos.logback.core.encoder.EncoderBase");
        types.add("ch.qos.logback.core.encoder.LayoutWrappingEncoder");
        types.add("ch.qos.logback.core.filter.AbstractMatcherFilter");
        types.add("ch.qos.logback.core.filter.EvaluatorFilter");
        types.add("ch.qos.logback.core.filter.Filter");
        types.add("ch.qos.logback.core.helpers.CyclicBuffer");
        types.add("ch.qos.logback.core.helpers.NOPAppender");
        types.add("ch.qos.logback.core.helpers.ThrowableToStringArray");
        types.add("ch.qos.logback.core.joran.GenericConfigurator");
        types.add("ch.qos.logback.core.joran.JoranConfiguratorBase");
        types.add("ch.qos.logback.core.joran.action.AbstractEventEvaluatorAction");
        types.add("ch.qos.logback.core.joran.action.Action");
        types.add("ch.qos.logback.core.joran.action.ActionUtil");
        types.add("ch.qos.logback.core.joran.action.ActionUtil$1");
        types.add("ch.qos.logback.core.joran.action.ActionUtil$Scope");
        types.add("ch.qos.logback.core.joran.action.AppenderAction");
        types.add("ch.qos.logback.core.joran.action.AppenderRefAction");
        types.add("ch.qos.logback.core.joran.action.ContextPropertyAction");
        types.add("ch.qos.logback.core.joran.action.ConversionRuleAction");
        types.add("ch.qos.logback.core.joran.action.DefinePropertyAction");
        types.add("ch.qos.logback.core.joran.action.IADataForBasicProperty");
        types.add("ch.qos.logback.core.joran.action.IADataForComplexProperty");
        types.add("ch.qos.logback.core.joran.action.ImplicitAction");
        types.add("ch.qos.logback.core.joran.action.IncludeAction");
        types.add("ch.qos.logback.core.joran.action.NOPAction");
        types.add("ch.qos.logback.core.joran.action.NestedBasicPropertyIA");
        types.add("ch.qos.logback.core.joran.action.NestedBasicPropertyIA$1");
        types.add("ch.qos.logback.core.joran.action.NestedComplexPropertyIA");
        types.add("ch.qos.logback.core.joran.action.NestedComplexPropertyIA$1");
        types.add("ch.qos.logback.core.joran.action.NewRuleAction");
        types.add("ch.qos.logback.core.joran.action.ParamAction");
        types.add("ch.qos.logback.core.joran.action.PropertyAction");
        types.add("ch.qos.logback.core.joran.action.StatusListenerAction");
        types.add("ch.qos.logback.core.joran.action.TimestampAction");
        types.add("ch.qos.logback.core.joran.conditional.Condition");
        types.add("ch.qos.logback.core.joran.conditional.ElseAction");
        types.add("ch.qos.logback.core.joran.conditional.IfAction");
        types.add("ch.qos.logback.core.joran.conditional.IfState");
        types.add("ch.qos.logback.core.joran.conditional.PropertyEvalScriptBuilder");
        types.add("ch.qos.logback.core.joran.conditional.PropertyWrapperForScripts");
        types.add("ch.qos.logback.core.joran.conditional.ThenAction");
        types.add("ch.qos.logback.core.joran.conditional.ThenActionState");
        types.add("ch.qos.logback.core.joran.conditional.ThenOrElseActionBase");
        types.add("ch.qos.logback.core.joran.event.BodyEvent");
        types.add("ch.qos.logback.core.joran.event.EndEvent");
        types.add("ch.qos.logback.core.joran.event.InPlayListener");
        types.add("ch.qos.logback.core.joran.event.SaxEvent");
        types.add("ch.qos.logback.core.joran.event.SaxEventRecorder");
        types.add("ch.qos.logback.core.joran.event.StartEvent");
        types.add("ch.qos.logback.core.joran.spi.ActionException");
        types.add("ch.qos.logback.core.joran.spi.CAI_WithLocatorSupport");
        types.add("ch.qos.logback.core.joran.spi.ConfigurationWatchList");
        types.add("ch.qos.logback.core.joran.spi.ConsoleTarget");
        types.add("ch.qos.logback.core.joran.spi.ConsoleTarget$1");
        types.add("ch.qos.logback.core.joran.spi.ConsoleTarget$2");
        types.add("ch.qos.logback.core.joran.spi.DefaultClass");
        types.add("ch.qos.logback.core.joran.spi.DefaultNestedComponentRegistry");
        types.add("ch.qos.logback.core.joran.spi.EventPlayer");
        types.add("ch.qos.logback.core.joran.spi.HostClassAndPropertyDouble");
        types.add("ch.qos.logback.core.joran.spi.InterpretationContext");
        types.add("ch.qos.logback.core.joran.spi.Interpreter");
        types.add("ch.qos.logback.core.joran.spi.JoranException");
        types.add("ch.qos.logback.core.joran.spi.NoAutoStart");
        types.add("ch.qos.logback.core.joran.spi.NoAutoStartUtil");
        types.add("ch.qos.logback.core.joran.spi.Pattern");
        types.add("ch.qos.logback.core.joran.spi.RuleStore");
        types.add("ch.qos.logback.core.joran.spi.SimpleRuleStore");
        types.add("ch.qos.logback.core.joran.util.ConfigurationWatchListUtil");
        types.add("ch.qos.logback.core.joran.util.PropertySetter");
        types.add("ch.qos.logback.core.joran.util.PropertySetter$1");
        types.add("ch.qos.logback.core.joran.util.StringToObjectConverter");
        types.add("ch.qos.logback.core.net.SocketAppenderBase");
        types.add("ch.qos.logback.core.net.SocketAppenderBase$Connector");
        types.add("ch.qos.logback.core.pattern.CompositeConverter");
        types.add("ch.qos.logback.core.pattern.Converter");
        types.add("ch.qos.logback.core.pattern.ConverterUtil");
        types.add("ch.qos.logback.core.pattern.DynamicConverter");
        types.add("ch.qos.logback.core.pattern.FormatInfo");
        types.add("ch.qos.logback.core.pattern.FormattingConverter");
        types.add("ch.qos.logback.core.pattern.IdentityCompositeConverter");
        types.add("ch.qos.logback.core.pattern.LiteralConverter");
        types.add("ch.qos.logback.core.pattern.PatternLayoutBase");
        types.add("ch.qos.logback.core.pattern.PatternLayoutEncoderBase");
        types.add("ch.qos.logback.core.pattern.PostCompileProcessor");
        types.add("ch.qos.logback.core.pattern.ReplacingCompositeConverter");
        types.add("ch.qos.logback.core.pattern.SpacePadder");
        types.add("ch.qos.logback.core.pattern.color.BlackCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BlueCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BoldBlueCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BoldCyanCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BoldGreenCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BoldMagentaCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BoldRedCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BoldWhiteCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.BoldYellowCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.CyanCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase");
        types.add("ch.qos.logback.core.pattern.color.GreenCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.MagentaCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.RedCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.WhiteCompositeConverter");
        types.add("ch.qos.logback.core.pattern.color.YellowCompositeConverter");
        types.add("ch.qos.logback.core.pattern.parser.Compiler");
        types.add("ch.qos.logback.core.pattern.parser.CompositeNode");
        types.add("ch.qos.logback.core.pattern.parser.FormattingNode");
        types.add("ch.qos.logback.core.pattern.parser.Node");
        types.add("ch.qos.logback.core.pattern.parser.OptionTokenizer");
        types.add("ch.qos.logback.core.pattern.parser.Parser");
        types.add("ch.qos.logback.core.pattern.parser.SimpleKeywordNode");
        types.add("ch.qos.logback.core.pattern.parser.Token");
        types.add("ch.qos.logback.core.pattern.parser.TokenStream");
        types.add("ch.qos.logback.core.pattern.parser.TokenStream$1");
        types.add("ch.qos.logback.core.pattern.parser.TokenStream$TokenizerState");
        types.add("ch.qos.logback.core.pattern.util.AsIsEscapeUtil");
        types.add("ch.qos.logback.core.pattern.util.IEscapeUtil");
        types.add("ch.qos.logback.core.pattern.util.RegularEscapeUtil");
        types.add("ch.qos.logback.core.pattern.util.RestrictedEscapeUtil");
        types.add("ch.qos.logback.core.sift.AppenderFactoryBase");
        types.add("ch.qos.logback.core.sift.AppenderTracker");
        types.add("ch.qos.logback.core.sift.AppenderTrackerImpl");
        types.add("ch.qos.logback.core.sift.AppenderTrackerImpl$Entry");
        types.add("ch.qos.logback.core.sift.Discriminator");
        types.add("ch.qos.logback.core.sift.SiftingAppenderBase");
        types.add("ch.qos.logback.core.sift.SiftingJoranConfiguratorBase");
        types.add("ch.qos.logback.core.spi.AppenderAttachable");
        types.add("ch.qos.logback.core.spi.AppenderAttachableImpl");
        types.add("ch.qos.logback.core.spi.ContextAware");
        types.add("ch.qos.logback.core.spi.ContextAwareBase");
        types.add("ch.qos.logback.core.spi.ContextAwareImpl");
        types.add("ch.qos.logback.core.spi.DeferredProcessingAware");
        // FilterAttachable interface stripped by proguard, see method comment
        if (exists("ch.qos.logback.core.spi.FilterAttachable")) {
            types.add("ch.qos.logback.core.spi.FilterAttachable");
        }
        types.add("ch.qos.logback.core.spi.FilterAttachableImpl");
        types.add("ch.qos.logback.core.spi.FilterReply");
        types.add("ch.qos.logback.core.spi.LifeCycle");
        types.add("ch.qos.logback.core.spi.LogbackLock");
        types.add("ch.qos.logback.core.spi.PreSerializationTransformer");
        types.add("ch.qos.logback.core.spi.PropertyContainer");
        types.add("ch.qos.logback.core.spi.PropertyDefiner");
        types.add("ch.qos.logback.core.spi.ScanException");
        types.add("ch.qos.logback.core.status.ErrorStatus");
        types.add("ch.qos.logback.core.status.InfoStatus");
        types.add("ch.qos.logback.core.status.OnConsoleStatusListener");
        types.add("ch.qos.logback.core.status.OnPrintStreamStatusListenerBase");
        types.add("ch.qos.logback.core.status.Status");
        types.add("ch.qos.logback.core.status.StatusBase");
        types.add("ch.qos.logback.core.status.StatusChecker");
        types.add("ch.qos.logback.core.status.StatusListener");
        types.add("ch.qos.logback.core.status.StatusManager");
        types.add("ch.qos.logback.core.status.StatusUtil");
        types.add("ch.qos.logback.core.status.WarnStatus");
        types.add("ch.qos.logback.core.subst.Node");
        types.add("ch.qos.logback.core.subst.Node$1");
        types.add("ch.qos.logback.core.subst.Node$Type");
        types.add("ch.qos.logback.core.subst.NodeToStringTransformer");
        types.add("ch.qos.logback.core.subst.NodeToStringTransformer$1");
        types.add("ch.qos.logback.core.subst.Parser");
        types.add("ch.qos.logback.core.subst.Parser$1");
        types.add("ch.qos.logback.core.subst.Token");
        types.add("ch.qos.logback.core.subst.Token$Type");
        types.add("ch.qos.logback.core.subst.Tokenizer");
        types.add("ch.qos.logback.core.subst.Tokenizer$1");
        types.add("ch.qos.logback.core.subst.Tokenizer$TokenizerState");
        types.add("ch.qos.logback.core.util.AggregationType");
        types.add("ch.qos.logback.core.util.CachingDateFormatter");
        types.add("ch.qos.logback.core.util.ContextUtil");
        types.add("ch.qos.logback.core.util.Duration");
        types.add("ch.qos.logback.core.util.DynamicClassLoadingException");
        types.add("ch.qos.logback.core.util.EnvUtil");
        types.add("ch.qos.logback.core.util.IncompatibleClassException");
        types.add("ch.qos.logback.core.util.Loader");
        types.add("ch.qos.logback.core.util.Loader$1");
        types.add("ch.qos.logback.core.util.OptionHelper");
        types.add("ch.qos.logback.core.util.PropertySetterException");
        types.add("ch.qos.logback.core.util.StatusPrinter");
        types.add("com.google.common.base.Ascii");
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
        types.add("com.google.common.base.Stopwatch");
        types.add("com.google.common.base.Stopwatch$1");
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
        types.add("com.google.common.collect.Collections2");
        types.add("com.google.common.collect.Collections2$1");
        types.add("com.google.common.collect.ComparatorOrdering");
        types.add("com.google.common.collect.DescendingImmutableSortedSet");
        types.add("com.google.common.collect.EmptyImmutableBiMap");
        types.add("com.google.common.collect.EmptyImmutableList");
        types.add("com.google.common.collect.EmptyImmutableSet");
        types.add("com.google.common.collect.EmptyImmutableSortedMap");
        types.add("com.google.common.collect.EmptyImmutableSortedSet");
        types.add("com.google.common.collect.FluentIterable");
        types.add("com.google.common.collect.Hashing");
        types.add("com.google.common.collect.ImmutableAsList");
        types.add("com.google.common.collect.ImmutableBiMap");
        types.add("com.google.common.collect.ImmutableCollection");
        types.add("com.google.common.collect.ImmutableCollection$1");
        types.add("com.google.common.collect.ImmutableCollection$Builder");
        types.add("com.google.common.collect.ImmutableCollection$EmptyImmutableCollection");
        types.add("com.google.common.collect.ImmutableEntry");
        types.add("com.google.common.collect.ImmutableEnumMap");
        types.add("com.google.common.collect.ImmutableEnumMap$1");
        types.add("com.google.common.collect.ImmutableEnumMap$2");
        types.add("com.google.common.collect.ImmutableEnumMap$2$1");
        types.add("com.google.common.collect.ImmutableList");
        types.add("com.google.common.collect.ImmutableList$1");
        types.add("com.google.common.collect.ImmutableList$Builder");
        types.add("com.google.common.collect.ImmutableList$SubList");
        types.add("com.google.common.collect.ImmutableMap");
        types.add("com.google.common.collect.ImmutableMap$Builder");
        types.add("com.google.common.collect.ImmutableMapEntrySet");
        types.add("com.google.common.collect.ImmutableMapKeySet");
        types.add("com.google.common.collect.ImmutableMapKeySet$1");
        types.add("com.google.common.collect.ImmutableMapValues");
        types.add("com.google.common.collect.ImmutableMapValues$1");
        types.add("com.google.common.collect.ImmutableSet");
        types.add("com.google.common.collect.ImmutableSortedMap");
        types.add("com.google.common.collect.ImmutableSortedMapFauxverideShim");
        types.add("com.google.common.collect.ImmutableSortedSet");
        types.add("com.google.common.collect.ImmutableSortedSetFauxverideShim");
        types.add("com.google.common.collect.Iterables");
        types.add("com.google.common.collect.Iterables$8");
        types.add("com.google.common.collect.Iterators");
        types.add("com.google.common.collect.Iterators$1");
        types.add("com.google.common.collect.Iterators$12");
        types.add("com.google.common.collect.Iterators$13");
        types.add("com.google.common.collect.Iterators$2");
        types.add("com.google.common.collect.Iterators$3");
        types.add("com.google.common.collect.Iterators$8");
        types.add("com.google.common.collect.Lists");
        types.add("com.google.common.collect.Lists$RandomAccessReverseList");
        types.add("com.google.common.collect.Lists$ReverseList");
        types.add("com.google.common.collect.Lists$ReverseList$1");
        types.add("com.google.common.collect.Maps");
        types.add("com.google.common.collect.Maps$8");
        types.add("com.google.common.collect.Maps$9");
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
        types.add("com.google.common.collect.RegularImmutableMap$LinkedEntry");
        types.add("com.google.common.collect.RegularImmutableMap$NonTerminalEntry");
        types.add("com.google.common.collect.RegularImmutableMap$TerminalEntry");
        types.add("com.google.common.collect.ReverseNaturalOrdering");
        types.add("com.google.common.collect.ReverseOrdering");
        types.add("com.google.common.collect.Sets");
        types.add("com.google.common.collect.SingletonImmutableBiMap");
        types.add("com.google.common.collect.SingletonImmutableList");
        types.add("com.google.common.collect.SingletonImmutableSet");
        types.add("com.google.common.collect.SortedIterable");
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
        types.add("io.informant.api.MetricName");
        types.add("io.informant.api.MetricTimer");
        types.add("io.informant.api.weaving.InjectMethodArg");
        types.add("io.informant.api.weaving.InjectMethodArgArray");
        types.add("io.informant.api.weaving.InjectMethodName");
        types.add("io.informant.api.weaving.InjectReturn");
        types.add("io.informant.api.weaving.InjectTarget");
        types.add("io.informant.api.weaving.InjectThrowable");
        types.add("io.informant.api.weaving.InjectTraveler");
        types.add("io.informant.api.weaving.IsEnabled");
        types.add("io.informant.api.weaving.MethodModifier");
        types.add("io.informant.api.weaving.Mixin");
        types.add("io.informant.api.weaving.OnAfter");
        types.add("io.informant.api.weaving.OnBefore");
        types.add("io.informant.api.weaving.OnReturn");
        types.add("io.informant.api.weaving.OnThrow");
        types.add("io.informant.api.weaving.Pointcut");
        types.add("io.informant.core.trace.Metric");
        types.add("io.informant.core.trace.MetricNameImpl");
        types.add("io.informant.core.trace.WeavingMetricNameImpl");
        types.add("io.informant.core.trace.WeavingMetricNameImpl$NopMetricTimer");
        types.add("io.informant.weaving.Advice");
        types.add("io.informant.weaving.Advice$ParameterKind");
        types.add("io.informant.weaving.AdviceFlowOuterHolder");
        types.add("io.informant.weaving.AdviceFlowOuterHolder$1");
        types.add("io.informant.weaving.AdviceFlowOuterHolder$2");
        types.add("io.informant.weaving.AdviceFlowOuterHolder$AdviceFlowHolder");
        types.add("io.informant.weaving.AdviceMatcher");
        types.add("io.informant.weaving.MixinMatcher");
        types.add("io.informant.weaving.ParsedMethod");
        types.add("io.informant.weaving.ParsedType");
        types.add("io.informant.weaving.ParsedType$Builder");
        types.add("io.informant.weaving.ParsedTypeCache");
        types.add("io.informant.weaving.ParsedTypeCache$1");
        types.add("io.informant.weaving.ParsedTypeCache$ParsedTypeClassVisitor");
        types.add("io.informant.weaving.PreInitializeClasses");
        types.add("io.informant.weaving.TypeNames");
        types.add("io.informant.weaving.Weaver");
        types.add("io.informant.weaving.WeavingClassFileTransformer");
        types.add("io.informant.weaving.WeavingClassFileTransformer$1");
        types.add("io.informant.weaving.WeavingClassVisitor");
        types.add("io.informant.weaving.WeavingClassVisitor$InitMixins");
        types.add("io.informant.weaving.WeavingClassVisitor$InitThreadLocals");
        types.add("io.informant.weaving.WeavingMethodVisitor");
        types.add("io.informant.weaving.WeavingMethodVisitor$MarkerException");
        types.add("io.informant.weaving.WeavingMetric");
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
        types.add("org.objectweb.asm.commons.LocalVariablesSorter");
        types.add("org.objectweb.asm.commons.Method");
        types.add("org.objectweb.asm.signature.SignatureReader");
        types.add("org.objectweb.asm.signature.SignatureVisitor");
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
        types.add("org.slf4j.ILoggerFactory");
        types.add("org.slf4j.Logger");
        types.add("org.slf4j.LoggerFactory");
        types.add("org.slf4j.MDC");
        types.add("org.slf4j.Marker");
        types.add("org.slf4j.helpers.FormattingTuple");
        types.add("org.slf4j.helpers.MarkerIgnoringBase");
        types.add("org.slf4j.helpers.MessageFormatter");
        types.add("org.slf4j.helpers.NOPLogger");
        types.add("org.slf4j.helpers.NOPLoggerFactory");
        types.add("org.slf4j.helpers.NOPMDCAdapter");
        types.add("org.slf4j.helpers.NamedLoggerBase");
        types.add("org.slf4j.helpers.SubstituteLoggerFactory");
        types.add("org.slf4j.helpers.Util");
        types.add("org.slf4j.impl.StaticLoggerBinder");
        types.add("org.slf4j.impl.StaticMDCBinder");
        types.add("org.slf4j.spi.LocationAwareLogger");
        // LoggerFactoryBinder interface stripped by proguard, see method comment
        if (exists("org.slf4j.spi.LoggerFactoryBinder")) {
            types.add("org.slf4j.spi.LoggerFactoryBinder");
        }
        types.add("org.slf4j.spi.MDCAdapter");
        if (exists("io.informant.shaded.logback.slf4j.Logger")) {
            types.add("io.informant.shaded.logback.slf4j.ILoggerFactory");
            types.add("io.informant.shaded.logback.slf4j.Logger");
            types.add("io.informant.shaded.logback.slf4j.MDC");
            types.add("io.informant.shaded.logback.slf4j.Marker");
            types.add("io.informant.shaded.logback.slf4j.helpers.FormattingTuple");
            types.add("io.informant.shaded.logback.slf4j.helpers.MessageFormatter");
            types.add("io.informant.shaded.logback.slf4j.helpers.NOPMDCAdapter");
            types.add("io.informant.shaded.logback.slf4j.helpers.Util");
            types.add("io.informant.shaded.logback.slf4j.impl.StaticLoggerBinder");
            types.add("io.informant.shaded.logback.slf4j.impl.StaticMDCBinder");
            types.add("io.informant.shaded.logback.slf4j.spi.LocationAwareLogger");
            // LoggerFactoryBinder interface stripped by proguard, see method comment
            if (exists("io.informant.shaded.logback.slf4j.spi.LoggerFactoryBinder")) {
                types.add("io.informant.shaded.logback.slf4j.spi.LoggerFactoryBinder");
            }
            types.add("io.informant.shaded.logback.slf4j.spi.MDCAdapter");
        }
        return types.build();
    }

    @VisibleForTesting
    static ImmutableList<String> maybeUsedTypes() {
        ImmutableList.Builder<String> types = ImmutableList.builder();
        // this is a special class generated by javac (but not by the eclipse compiler) to handle
        // accessing the private constructor in an enclosed type
        // (see http://stackoverflow.com/questions/2883181)
        types.add("io.informant.weaving.ParsedType$1");
        // this is a special class generated by javac (but not by the eclipse compiler) to handle
        // enum switch statements
        // (see http://stackoverflow.com/questions/1834632/java-enum-and-additional-class-files)
        types.add("io.informant.weaving.AdviceMatcher$1");
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
