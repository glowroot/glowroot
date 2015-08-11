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
package org.glowroot.tests;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.api.Glowroot;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.Container;
import org.glowroot.container.TraceMarker;
import org.glowroot.container.trace.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class GlowrootApiTest {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldSetTransactionType() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionType.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getTransactionType()).isEqualTo("a type");
    }

    @Test
    public void shouldSetTransactionName() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionName.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getTransactionName()).isEqualTo("a name");
    }

    @Test
    public void shouldSetTransactionErrorWithThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionErrorWithThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getErrorMessage()).isEqualTo("abc");
        assertThat(trace.getErrorThrowable().getDisplay())
                .isEqualTo("java.lang.IllegalStateException: abc");
    }

    @Test
    public void shouldSetTransactionErrorWithMessage() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionErrorWithMessage.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getErrorMessage()).isEqualTo("xyz");
        assertThat(trace.getErrorThrowable()).isNull();
    }

    @Test
    public void shouldSetTransactionErrorWithMessageAndThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionErrorWithMessageAndThrowable.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getErrorMessage()).isEqualTo("efg");
        assertThat(trace.getErrorThrowable().getDisplay())
                .isEqualTo("java.lang.IllegalStateException: tuv");
    }

    @Test
    public void shouldSetTransactionUser() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionUser.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getUser()).isEqualTo("a user");
    }

    @Test
    public void shouldAddTransactionCustomAttribute() throws Exception {
        // given
        // when
        container.executeAppUnderTest(AddTransactionCustomAttribute.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace.getCustomAttributes().size()).isEqualTo(1);
        assertThat(trace.getCustomAttributes().get("an attr")).containsExactly("a val");
    }

    @Test
    public void shouldSetTransactionSlowThreshold() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionSlowThreshold.class);
        // then
        Trace trace = container.getTraceService().getLastTrace();
        assertThat(trace).isNull();
    }

    public static class SetTransactionType implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.setTransactionType("a type");
        }
    }

    public static class SetTransactionName implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.setTransactionName("a name");
        }
    }

    public static class SetTransactionErrorWithThrowable implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.setTransactionError(new IllegalStateException("abc"));
        }
    }

    public static class SetTransactionErrorWithMessage implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.setTransactionError("xyz");
        }
    }

    public static class SetTransactionErrorWithMessageAndThrowable
            implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.setTransactionError("efg", new IllegalStateException("tuv"));
        }
    }

    public static class SetTransactionUser implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.setTransactionUser("a user");
        }
    }

    public static class AddTransactionCustomAttribute implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.addTransactionCustomAttribute("an attr", "a val");
        }
    }

    public static class SetTransactionSlowThreshold implements AppUnderTest, TraceMarker {

        @Override
        public void executeApp() throws Exception {
            traceMarker();
        }

        @Override
        public void traceMarker() throws Exception {
            Glowroot.setTransactionSlowThreshold(Long.MAX_VALUE, MILLISECONDS);
        }
    }
}
