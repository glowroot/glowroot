/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.jul;

import java.lang.reflect.Method;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.immutables.value.Value;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class LoggerTest {

    @Test
    public void testImplementsAllMethods() {
        Set<MethodSignature> glowrootLoggerMethods = Sets.newHashSet();
        for (Method method : Logger.class.getMethods()) {
            ImmutableMethodSignature.Builder builder = ImmutableMethodSignature.builder()
                    .name(method.getName());
            for (Class<?> clazz : method.getParameterTypes()) {
                String parameterType = clazz.getName();
                builder.addParameterTypes(parameterType.replaceFirst("org.glowroot.agent.jul.",
                        "java.util.logging."));
            }
            glowrootLoggerMethods.add(builder
                    .returnType(method.getReturnType().getName()
                            .replaceFirst("org.glowroot.agent.jul.", "java.util.logging."))
                    .build());
        }
        Set<MethodSignature> loggerMethods = Sets.newHashSet();
        for (Method method : java.util.logging.Logger.class.getMethods()) {
            String methodName = method.getName();
            if (methodName.contains("Handler") || methodName.contains("Filter")) {
                continue;
            }
            ImmutableMethodSignature.Builder builder = ImmutableMethodSignature.builder()
                    .name(methodName);
            List<String> parameterTypes = Lists.newArrayList();
            for (Class<?> clazz : method.getParameterTypes()) {
                parameterTypes.add(clazz.getName());
            }
            if (parameterTypes.contains("java.util.function.Supplier")) {
                // FIXME it would be good to include Java 8 methods
                continue;
            }
            loggerMethods.add(builder
                    .addAllParameterTypes(parameterTypes)
                    .returnType(method.getReturnType().getName())
                    .build());
        }
        assertThat(glowrootLoggerMethods).containsAll(loggerMethods);
    }

    @Test
    public void testGetLogger() {
        Logger logger = Logger.getLogger("abc");
        assertThat(logger.getSlf4jLogger().getName()).isEqualTo("abc");
    }

    @Test
    public void testGetName() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.getName()).thenReturn("xyz");
        // when
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getName()).isEqualTo("xyz");
    }

    @Test
    public void testNormalMethods() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.severe("ereves");
        logger.warning("gninraw");
        logger.info("ofni");
        logger.config("gifnoc");
        logger.fine("enif");
        logger.finer("renif");
        logger.finest("tsenif");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves");
        inOrder.verify(slf4jLogger).warn("gninraw");
        inOrder.verify(slf4jLogger).info("ofni");
        inOrder.verify(slf4jLogger).info("gifnoc");
        inOrder.verify(slf4jLogger).debug("enif");
        inOrder.verify(slf4jLogger).trace("renif");
        inOrder.verify(slf4jLogger).trace("tsenif");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testParameterizedLevelMethodsWithNoParams() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.log(Level.SEVERE, "ereves");
        logger.log(Level.WARNING, "gninraw");
        logger.log(Level.INFO, "ofni");
        logger.log(Level.CONFIG, "gifnoc");
        logger.log(Level.FINE, "enif");
        logger.log(Level.FINER, "renif");
        logger.log(Level.FINEST, "tsenif");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves");
        inOrder.verify(slf4jLogger).warn("gninraw");
        inOrder.verify(slf4jLogger).info("ofni");
        inOrder.verify(slf4jLogger).info("gifnoc");
        inOrder.verify(slf4jLogger).debug("enif");
        inOrder.verify(slf4jLogger).trace("renif");
        inOrder.verify(slf4jLogger).trace("tsenif");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testParameterizedLevelMethodsWithSingleParam() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.log(Level.SEVERE, "ereves: {0}", "a");
        logger.log(Level.WARNING, "gninraw: {0}", "b");
        logger.log(Level.INFO, "ofni: {0}", "c");
        logger.log(Level.CONFIG, "gifnoc: {0}", "d");
        logger.log(Level.FINE, "enif: {0}", "e");
        logger.log(Level.FINER, "renif: {0}", "f");
        logger.log(Level.FINEST, "tsenif: {0}", "g");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testParameterizedLevelMethodsWithArrayOfParams() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.log(Level.SEVERE, "ereves: {0},{1}", new Object[] {"a", "b"});
        logger.log(Level.WARNING, "gninraw: {0},{1}", new Object[] {"b", "c"});
        logger.log(Level.INFO, "ofni: {0},{1}", new Object[] {"c", "d"});
        logger.log(Level.CONFIG, "gifnoc: {0},{1}", new Object[] {"d", "e"});
        logger.log(Level.FINE, "enif: {0},{1}", new Object[] {"e", "f"});
        logger.log(Level.FINER, "renif: {0},{1}", new Object[] {"f", "g"});
        logger.log(Level.FINEST, "tsenif: {0},{1}", new Object[] {"g", "h"});

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a,b");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b,c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c,d");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d,e");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e,f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f,g");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g,h");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testParameterizedLevelMethodsWithThrowable() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        Throwable a = new Throwable();
        Throwable b = new Throwable();
        Throwable c = new Throwable();
        Throwable d = new Throwable();
        Throwable e = new Throwable();
        Throwable f = new Throwable();
        Throwable g = new Throwable();

        // when
        logger.log(Level.SEVERE, "ereves", a);
        logger.log(Level.WARNING, "gninraw", b);
        logger.log(Level.INFO, "ofni", c);
        logger.log(Level.CONFIG, "gifnoc", d);
        logger.log(Level.FINE, "enif", e);
        logger.log(Level.FINER, "renif", f);
        logger.log(Level.FINEST, "tsenif", g);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves", a);
        inOrder.verify(slf4jLogger).warn("gninraw", b);
        inOrder.verify(slf4jLogger).info("ofni", c);
        inOrder.verify(slf4jLogger).info("gifnoc", d);
        inOrder.verify(slf4jLogger).debug("enif", e);
        inOrder.verify(slf4jLogger).trace("renif", f);
        inOrder.verify(slf4jLogger).trace("tsenif", g);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testIsLoggableAll() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);

        // when
        Logger logger = new Logger(slf4jLogger);

        // then
        assertThat(logger.isLoggable(Level.SEVERE)).isTrue();
        assertThat(logger.isLoggable(Level.WARNING)).isTrue();
        assertThat(logger.isLoggable(Level.INFO)).isTrue();
        assertThat(logger.isLoggable(Level.CONFIG)).isTrue();
        assertThat(logger.isLoggable(Level.FINE)).isTrue();
        assertThat(logger.isLoggable(Level.FINER)).isTrue();
        assertThat(logger.isLoggable(Level.FINEST)).isTrue();
    }

    @Test
    public void testIsLoggableSome() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(false);
        when(slf4jLogger.isDebugEnabled()).thenReturn(false);
        when(slf4jLogger.isInfoEnabled()).thenReturn(false);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);

        // when
        Logger logger = new Logger(slf4jLogger);

        // then
        assertThat(logger.isLoggable(Level.SEVERE)).isTrue();
        assertThat(logger.isLoggable(Level.WARNING)).isTrue();
        assertThat(logger.isLoggable(Level.INFO)).isFalse();
        assertThat(logger.isLoggable(Level.CONFIG)).isFalse();
        assertThat(logger.isLoggable(Level.FINE)).isFalse();
        assertThat(logger.isLoggable(Level.FINER)).isFalse();
        assertThat(logger.isLoggable(Level.FINEST)).isFalse();
    }

    @Test
    public void testIsLoggableNone() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(false);
        when(slf4jLogger.isDebugEnabled()).thenReturn(false);
        when(slf4jLogger.isInfoEnabled()).thenReturn(false);
        when(slf4jLogger.isWarnEnabled()).thenReturn(false);
        when(slf4jLogger.isErrorEnabled()).thenReturn(false);

        // when
        Logger logger = new Logger(slf4jLogger);

        // then
        assertThat(logger.isLoggable(Level.SEVERE)).isFalse();
        assertThat(logger.isLoggable(Level.WARNING)).isFalse();
        assertThat(logger.isLoggable(Level.INFO)).isFalse();
        assertThat(logger.isLoggable(Level.CONFIG)).isFalse();
        assertThat(logger.isLoggable(Level.FINE)).isFalse();
        assertThat(logger.isLoggable(Level.FINER)).isFalse();
        assertThat(logger.isLoggable(Level.FINEST)).isFalse();
    }

    @Test
    public void testGetLevelSevere() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        // when
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.SEVERE);
    }

    @Test
    public void testGetLevelWarning() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        // when
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.WARNING);
    }

    @Test
    public void testGetLevelConfig() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        // when
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.CONFIG);
    }

    @Test
    public void testGetLevelFine() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        // when
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.FINE);
    }

    @Test
    public void testGetLevelFinest() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        // when
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.FINEST);
    }

    @Test
    public void testGetLevelOff() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        // when
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.OFF);
    }

    @Test
    public void testLogpParameterizedLevelMethodsWithNoParams() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logp(Level.SEVERE, null, null, "ereves");
        logger.logp(Level.WARNING, null, null, "gninraw");
        logger.logp(Level.INFO, null, null, "ofni");
        logger.logp(Level.CONFIG, null, null, "gifnoc");
        logger.logp(Level.FINE, null, null, "enif");
        logger.logp(Level.FINER, null, null, "renif");
        logger.logp(Level.FINEST, null, null, "tsenif");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves");
        inOrder.verify(slf4jLogger).warn("gninraw");
        inOrder.verify(slf4jLogger).info("ofni");
        inOrder.verify(slf4jLogger).info("gifnoc");
        inOrder.verify(slf4jLogger).debug("enif");
        inOrder.verify(slf4jLogger).trace("renif");
        inOrder.verify(slf4jLogger).trace("tsenif");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogpParameterizedLevelMethodsWithSingleParam() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logp(Level.SEVERE, null, null, "ereves: {0}", "a");
        logger.logp(Level.WARNING, null, null, "gninraw: {0}", "b");
        logger.logp(Level.INFO, null, null, "ofni: {0}", "c");
        logger.logp(Level.CONFIG, null, null, "gifnoc: {0}", "d");
        logger.logp(Level.FINE, null, null, "enif: {0}", "e");
        logger.logp(Level.FINER, null, null, "renif: {0}", "f");
        logger.logp(Level.FINEST, null, null, "tsenif: {0}", "g");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogpParameterizedLevelMethodsWithArrayOfParams() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logp(Level.SEVERE, null, null, "ereves: {0},{1}", new Object[] {"a", "b"});
        logger.logp(Level.WARNING, null, null, "gninraw: {0},{1}", new Object[] {"b", "c"});
        logger.logp(Level.INFO, null, null, "ofni: {0},{1}", new Object[] {"c", "d"});
        logger.logp(Level.CONFIG, null, null, "gifnoc: {0},{1}", new Object[] {"d", "e"});
        logger.logp(Level.FINE, null, null, "enif: {0},{1}", new Object[] {"e", "f"});
        logger.logp(Level.FINER, null, null, "renif: {0},{1}", new Object[] {"f", "g"});
        logger.logp(Level.FINEST, null, null, "tsenif: {0},{1}", new Object[] {"g", "h"});

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a,b");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b,c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c,d");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d,e");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e,f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f,g");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g,h");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogpParameterizedLevelMethodsWithThrowable() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        Throwable a = new Throwable();
        Throwable b = new Throwable();
        Throwable c = new Throwable();
        Throwable d = new Throwable();
        Throwable e = new Throwable();
        Throwable f = new Throwable();
        Throwable g = new Throwable();

        // when
        logger.logp(Level.SEVERE, null, null, "ereves", a);
        logger.logp(Level.WARNING, null, null, "gninraw", b);
        logger.logp(Level.INFO, null, null, "ofni", c);
        logger.logp(Level.CONFIG, null, null, "gifnoc", d);
        logger.logp(Level.FINE, null, null, "enif", e);
        logger.logp(Level.FINER, null, null, "renif", f);
        logger.logp(Level.FINEST, null, null, "tsenif", g);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves", a);
        inOrder.verify(slf4jLogger).warn("gninraw", b);
        inOrder.verify(slf4jLogger).info("ofni", c);
        inOrder.verify(slf4jLogger).info("gifnoc", d);
        inOrder.verify(slf4jLogger).debug("enif", e);
        inOrder.verify(slf4jLogger).trace("renif", f);
        inOrder.verify(slf4jLogger).trace("tsenif", g);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithNoParams() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logrb(Level.SEVERE, null, null, null, "ereves");
        logger.logrb(Level.WARNING, null, null, null, "gninraw");
        logger.logrb(Level.INFO, null, null, null, "ofni");
        logger.logrb(Level.CONFIG, null, null, null, "gifnoc");
        logger.logrb(Level.FINE, null, null, null, "enif");
        logger.logrb(Level.FINER, null, null, null, "renif");
        logger.logrb(Level.FINEST, null, null, null, "tsenif");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves");
        inOrder.verify(slf4jLogger).warn("gninraw");
        inOrder.verify(slf4jLogger).info("ofni");
        inOrder.verify(slf4jLogger).info("gifnoc");
        inOrder.verify(slf4jLogger).debug("enif");
        inOrder.verify(slf4jLogger).trace("renif");
        inOrder.verify(slf4jLogger).trace("tsenif");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithSingleParam() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logrb(Level.SEVERE, null, null, null, "ereves: {0}", "a");
        logger.logrb(Level.WARNING, null, null, null, "gninraw: {0}", "b");
        logger.logrb(Level.INFO, null, null, null, "ofni: {0}", "c");
        logger.logrb(Level.CONFIG, null, null, null, "gifnoc: {0}", "d");
        logger.logrb(Level.FINE, null, null, null, "enif: {0}", "e");
        logger.logrb(Level.FINER, null, null, null, "renif: {0}", "f");
        logger.logrb(Level.FINEST, null, null, null, "tsenif: {0}", "g");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithArrayOfParams() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logrb(Level.SEVERE, null, null, (String) null, "ereves: {0},{1}",
                new Object[] {"a", "b"});
        logger.logrb(Level.WARNING, null, null, (String) null, "gninraw: {0},{1}",
                new Object[] {"b", "c"});
        logger.logrb(Level.INFO, null, null, (String) null, "ofni: {0},{1}",
                new Object[] {"c", "d"});
        logger.logrb(Level.CONFIG, null, null, (String) null, "gifnoc: {0},{1}",
                new Object[] {"d", "e"});
        logger.logrb(Level.FINE, null, null, (String) null, "enif: {0},{1}",
                new Object[] {"e", "f"});
        logger.logrb(Level.FINER, null, null, (String) null, "renif: {0},{1}",
                new Object[] {"f", "g"});
        logger.logrb(Level.FINEST, null, null, (String) null, "tsenif: {0},{1}",
                new Object[] {"g", "h"});

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a,b");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b,c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c,d");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d,e");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e,f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f,g");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g,h");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithVarArgsOfParams() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logrb(Level.SEVERE, (String) null, null, null, "ereves: {0},{1}", "a", "b");
        logger.logrb(Level.WARNING, (String) null, null, null, "gninraw: {0},{1}", "b", "c");
        logger.logrb(Level.INFO, (String) null, null, null, "ofni: {0},{1}", "c", "d");
        logger.logrb(Level.CONFIG, (String) null, null, null, "gifnoc: {0},{1}", "d", "e");
        logger.logrb(Level.FINE, (String) null, null, null, "enif: {0},{1}", "e", "f");
        logger.logrb(Level.FINER, (String) null, null, null, "renif: {0},{1}", "f", "g");
        logger.logrb(Level.FINEST, (String) null, null, null, "tsenif: {0},{1}", "g", "h");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a,b");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b,c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c,d");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d,e");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e,f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f,g");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g,h");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithVarArgsOfParams2() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.logrb(Level.SEVERE, (ResourceBundle) null, "ereves: {0},{1}", "a", "b");
        logger.logrb(Level.WARNING, (ResourceBundle) null, "gninraw: {0},{1}", "b",
                "c");
        logger.logrb(Level.INFO, (ResourceBundle) null, "ofni: {0},{1}", "c", "d");
        logger.logrb(Level.CONFIG, (ResourceBundle) null, "gifnoc: {0},{1}", "d", "e");
        logger.logrb(Level.FINE, (ResourceBundle) null, "enif: {0},{1}", "e", "f");
        logger.logrb(Level.FINER, (ResourceBundle) null, "renif: {0},{1}", "f", "g");
        logger.logrb(Level.FINEST, (ResourceBundle) null, "tsenif: {0},{1}", "g", "h");

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("ereves: a,b");
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("gninraw: b,c");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("ofni: c,d");
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("gifnoc: d,e");
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("enif: e,f");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("renif: f,g");
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("tsenif: g,h");
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithThrowable() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        Throwable a = new Throwable();
        Throwable b = new Throwable();
        Throwable c = new Throwable();
        Throwable d = new Throwable();
        Throwable e = new Throwable();
        Throwable f = new Throwable();
        Throwable g = new Throwable();

        // when
        logger.logrb(Level.SEVERE, null, null, (String) null, "ereves", a);
        logger.logrb(Level.WARNING, null, null, (String) null, "gninraw", b);
        logger.logrb(Level.INFO, null, null, (String) null, "ofni", c);
        logger.logrb(Level.CONFIG, null, null, (String) null, "gifnoc", d);
        logger.logrb(Level.FINE, null, null, (String) null, "enif", e);
        logger.logrb(Level.FINER, null, null, (String) null, "renif", f);
        logger.logrb(Level.FINEST, null, null, (String) null, "tsenif", g);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves", a);
        inOrder.verify(slf4jLogger).warn("gninraw", b);
        inOrder.verify(slf4jLogger).info("ofni", c);
        inOrder.verify(slf4jLogger).info("gifnoc", d);
        inOrder.verify(slf4jLogger).debug("enif", e);
        inOrder.verify(slf4jLogger).trace("renif", f);
        inOrder.verify(slf4jLogger).trace("tsenif", g);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithThrowable2() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        Throwable a = new Throwable();
        Throwable b = new Throwable();
        Throwable c = new Throwable();
        Throwable d = new Throwable();
        Throwable e = new Throwable();
        Throwable f = new Throwable();
        Throwable g = new Throwable();

        // when
        logger.logrb(Level.SEVERE, null, null, (ResourceBundle) null, "ereves", a);
        logger.logrb(Level.WARNING, null, null, (ResourceBundle) null, "gninraw", b);
        logger.logrb(Level.INFO, null, null, (ResourceBundle) null, "ofni", c);
        logger.logrb(Level.CONFIG, null, null, (ResourceBundle) null, "gifnoc", d);
        logger.logrb(Level.FINE, null, null, (ResourceBundle) null, "enif", e);
        logger.logrb(Level.FINER, null, null, (ResourceBundle) null, "renif", f);
        logger.logrb(Level.FINEST, null, null, (ResourceBundle) null, "tsenif", g);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves", a);
        inOrder.verify(slf4jLogger).warn("gninraw", b);
        inOrder.verify(slf4jLogger).info("ofni", c);
        inOrder.verify(slf4jLogger).info("gifnoc", d);
        inOrder.verify(slf4jLogger).debug("enif", e);
        inOrder.verify(slf4jLogger).trace("renif", f);
        inOrder.verify(slf4jLogger).trace("tsenif", g);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testLogrbParameterizedLevelMethodsWithResourceBundleObjectAndThrowable() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        Throwable a = new Throwable();
        Throwable b = new Throwable();
        Throwable c = new Throwable();
        Throwable d = new Throwable();
        Throwable e = new Throwable();
        Throwable f = new Throwable();
        Throwable g = new Throwable();

        // when
        logger.logrb(Level.SEVERE, null, null, (ResourceBundle) null, "ereves", a);
        logger.logrb(Level.WARNING, null, null, (ResourceBundle) null, "gninraw", b);
        logger.logrb(Level.INFO, null, null, (ResourceBundle) null, "ofni", c);
        logger.logrb(Level.CONFIG, null, null, (ResourceBundle) null, "gifnoc", d);
        logger.logrb(Level.FINE, null, null, (ResourceBundle) null, "enif", e);
        logger.logrb(Level.FINER, null, null, (ResourceBundle) null, "renif", f);
        logger.logrb(Level.FINEST, null, null, (ResourceBundle) null, "tsenif", g);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).error("ereves", a);
        inOrder.verify(slf4jLogger).warn("gninraw", b);
        inOrder.verify(slf4jLogger).info("ofni", c);
        inOrder.verify(slf4jLogger).info("gifnoc", d);
        inOrder.verify(slf4jLogger).debug("enif", e);
        inOrder.verify(slf4jLogger).trace("renif", f);
        inOrder.verify(slf4jLogger).trace("tsenif", g);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testEnteringExitingThrowingMethods() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);

        // when
        logger.entering(null, null);
        logger.entering(null, null, new Object());
        logger.entering(null, null, new Object[0]);
        logger.exiting(null, null);
        logger.exiting(null, null, new Object());
        logger.throwing(null, null, null);

        // then
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testResourceBundle() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);

        // when
        Logger logger = new Logger(slf4jLogger);

        // then
        assertThat(logger.getResourceBundle()).isNull();
        assertThat(logger.getResourceBundleName()).isNull();
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Value.Immutable
    interface MethodSignature {
        String name();
        List<String> parameterTypes();
        String returnType();
    }
}
