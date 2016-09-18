/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.api;

import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import org.glowroot.agent.plugin.api.Agent.LoggerImpl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class LoggerTest {

    @Test
    public void testName() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new LoggerImpl(slf4jLogger);

        // when
        logger.getName();

        // then
        verify(slf4jLogger).getName();
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testTraceMethods() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new LoggerImpl(slf4jLogger);
        Throwable t = new Throwable();

        // when
        logger.isTraceEnabled();
        logger.trace("a");
        logger.trace("a: {}", "b");
        logger.trace("a: {},{}", "b", "c");
        logger.trace("a: {},{},{}", "b", "c", "d");
        logger.trace("a", t);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isTraceEnabled();
        inOrder.verify(slf4jLogger).trace("a");
        inOrder.verify(slf4jLogger).trace("a: {}", "b");
        inOrder.verify(slf4jLogger).trace("a: {},{}", "b", "c");
        inOrder.verify(slf4jLogger).trace("a: {},{},{}", "b", "c", "d");
        inOrder.verify(slf4jLogger).trace("a", t);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testDebugMethods() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new LoggerImpl(slf4jLogger);
        Throwable t = new Throwable();

        // when
        logger.isDebugEnabled();
        logger.debug("a");
        logger.debug("a: {}", "b");
        logger.debug("a: {},{}", "b", "c");
        logger.debug("a: {},{},{}", "b", "c", "d");
        logger.debug("a", t);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isDebugEnabled();
        inOrder.verify(slf4jLogger).debug("a");
        inOrder.verify(slf4jLogger).debug("a: {}", "b");
        inOrder.verify(slf4jLogger).debug("a: {},{}", "b", "c");
        inOrder.verify(slf4jLogger).debug("a: {},{},{}", "b", "c", "d");
        inOrder.verify(slf4jLogger).debug("a", t);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testInfoMethods() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new LoggerImpl(slf4jLogger);
        Throwable t = new Throwable();

        // when
        logger.isInfoEnabled();
        logger.info("a");
        logger.info("a: {}", "b");
        logger.info("a: {},{}", "b", "c");
        logger.info("a: {},{},{}", "b", "c", "d");
        logger.info("a", t);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isInfoEnabled();
        inOrder.verify(slf4jLogger).info("a");
        inOrder.verify(slf4jLogger).info("a: {}", "b");
        inOrder.verify(slf4jLogger).info("a: {},{}", "b", "c");
        inOrder.verify(slf4jLogger).info("a: {},{},{}", "b", "c", "d");
        inOrder.verify(slf4jLogger).info("a", t);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testWarnMethods() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new LoggerImpl(slf4jLogger);
        Throwable t = new Throwable();

        // when
        logger.isWarnEnabled();
        logger.warn("a");
        logger.warn("a: {}", "b");
        logger.warn("a: {},{}", "b", "c");
        logger.warn("a: {},{},{}", "b", "c", "d");
        logger.warn("a", t);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isWarnEnabled();
        inOrder.verify(slf4jLogger).warn("a");
        inOrder.verify(slf4jLogger).warn("a: {}", "b");
        inOrder.verify(slf4jLogger).warn("a: {},{}", "b", "c");
        inOrder.verify(slf4jLogger).warn("a: {},{},{}", "b", "c", "d");
        inOrder.verify(slf4jLogger).warn("a", t);
        verifyNoMoreInteractions(slf4jLogger);
    }

    @Test
    public void testErrorMethods() {
        // given
        org.slf4j.Logger slf4jLogger = mock(org.slf4j.Logger.class);
        Logger logger = new LoggerImpl(slf4jLogger);
        Throwable t = new Throwable();

        // when
        logger.isErrorEnabled();
        logger.error("a");
        logger.error("a: {}", "b");
        logger.error("a: {},{}", "b", "c");
        logger.error("a: {},{},{}", "b", "c", "d");
        logger.error("a", t);

        // then
        InOrder inOrder = Mockito.inOrder(slf4jLogger);
        inOrder.verify(slf4jLogger).isErrorEnabled();
        inOrder.verify(slf4jLogger).error("a");
        inOrder.verify(slf4jLogger).error("a: {}", "b");
        inOrder.verify(slf4jLogger).error("a: {},{}", "b", "c");
        inOrder.verify(slf4jLogger).error("a: {},{},{}", "b", "c", "d");
        inOrder.verify(slf4jLogger).error("a", t);
        verifyNoMoreInteractions(slf4jLogger);
    }
}
