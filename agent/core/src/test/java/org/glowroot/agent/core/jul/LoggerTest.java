/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.core.jul;

import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import org.glowroot.agent.core.jul.Level;
import org.glowroot.agent.core.jul.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class LoggerTest {

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
        Logger logger = new Logger(slf4jLogger);
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
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
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
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isTraceEnabled()).thenReturn(false);
        when(slf4jLogger.isDebugEnabled()).thenReturn(false);
        when(slf4jLogger.isInfoEnabled()).thenReturn(false);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
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
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isTraceEnabled()).thenReturn(false);
        when(slf4jLogger.isDebugEnabled()).thenReturn(false);
        when(slf4jLogger.isInfoEnabled()).thenReturn(false);
        when(slf4jLogger.isWarnEnabled()).thenReturn(false);
        when(slf4jLogger.isErrorEnabled()).thenReturn(false);
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
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isErrorEnabled()).thenReturn(true);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.SEVERE);
    }

    @Test
    public void testGetLevelWarning() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isWarnEnabled()).thenReturn(true);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.WARNING);
    }

    @Test
    public void testGetLevelConfig() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isInfoEnabled()).thenReturn(true);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.CONFIG);
    }

    @Test
    public void testGetLevelFine() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isDebugEnabled()).thenReturn(true);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.FINE);
    }

    @Test
    public void testGetLevelFinest() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new Logger(slf4jLogger);
        when(slf4jLogger.isTraceEnabled()).thenReturn(true);
        // then
        assertThat(logger.getLevel()).isEqualTo(Level.FINEST);
    }

    @Test
    public void testGetLevelOff() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
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
        logger.logrb(Level.SEVERE, null, null, null, "ereves: {0},{1}", new Object[] {"a", "b"});
        logger.logrb(Level.WARNING, null, null, null, "gninraw: {0},{1}", new Object[] {"b", "c"});
        logger.logrb(Level.INFO, null, null, null, "ofni: {0},{1}", new Object[] {"c", "d"});
        logger.logrb(Level.CONFIG, null, null, null, "gifnoc: {0},{1}", new Object[] {"d", "e"});
        logger.logrb(Level.FINE, null, null, null, "enif: {0},{1}", new Object[] {"e", "f"});
        logger.logrb(Level.FINER, null, null, null, "renif: {0},{1}", new Object[] {"f", "g"});
        logger.logrb(Level.FINEST, null, null, null, "tsenif: {0},{1}", new Object[] {"g", "h"});
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
        logger.logrb(Level.SEVERE, null, null, null, "ereves", a);
        logger.logrb(Level.WARNING, null, null, null, "gninraw", b);
        logger.logrb(Level.INFO, null, null, null, "ofni", c);
        logger.logrb(Level.CONFIG, null, null, null, "gifnoc", d);
        logger.logrb(Level.FINE, null, null, null, "enif", e);
        logger.logrb(Level.FINER, null, null, null, "renif", f);
        logger.logrb(Level.FINEST, null, null, null, "tsenif", g);
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
        Logger logger = new Logger(slf4jLogger);
        // then
        assertThat(logger.getResourceBundle()).isNull();
        assertThat(logger.getResourceBundleName()).isNull();
        verifyNoMoreInteractions(slf4jLogger);
    }
}
