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
package org.glowroot.shaded.jul;

import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class LoggerTest {

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
    public void testGetLogger() {
        Logger logger = Logger.getLogger("abc");
        assertThat(logger.getLogger().getName()).isEqualTo("abc");
    }
}
