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
import org.glowroot.container.TransactionMarker;
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
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.transactionType()).isEqualTo("a type");
    }

    @Test
    public void shouldSetTransactionName() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionName.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.transactionName()).isEqualTo("a name");
    }

    @Test
    public void shouldSetTransactionErrorWithThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionErrorWithThrowable.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.error().get().message()).isEqualTo("abc");
        assertThat(header.error().get().exception().get().display())
                .isEqualTo("java.lang.IllegalStateException: abc");
    }

    @Test
    public void shouldSetTransactionErrorWithMessage() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionErrorWithMessage.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.error().get().message()).isEqualTo("xyz");
        assertThat(header.error().get().exception().isPresent()).isFalse();
    }

    @Test
    public void shouldSetTransactionErrorWithMessageAndThrowable() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionErrorWithMessageAndThrowable.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.error().get().message()).isEqualTo("efg");
        assertThat(header.error().get().exception().get().display())
                .isEqualTo("java.lang.IllegalStateException: tuv");
    }

    @Test
    public void shouldSetTransactionUser() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionUser.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.user()).isEqualTo("a user");
    }

    @Test
    public void shouldAddTransactionAttribute() throws Exception {
        // given
        // when
        container.executeAppUnderTest(AddTransactionAttribute.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header.attributes().size()).isEqualTo(1);
        assertThat(header.attributes().get("an attr")).containsExactly("a val");
    }

    @Test
    public void shouldSetTransactionSlowThreshold() throws Exception {
        // given
        // when
        container.executeAppUnderTest(SetTransactionSlowThreshold.class);
        // then
        Trace.Header header = container.getTraceService().getLastHeader();
        assertThat(header).isNull();
    }

    public static class SetTransactionType implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionType("a type");
        }
    }

    public static class SetTransactionName implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionName("a name");
        }
    }

    public static class SetTransactionErrorWithThrowable
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionError(new IllegalStateException("abc"));
        }
    }

    public static class SetTransactionErrorWithMessage implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionError("xyz");
        }
    }

    public static class SetTransactionErrorWithMessageAndThrowable
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionError("efg", new IllegalStateException("tuv"));
        }
    }

    public static class SetTransactionUser implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionUser("a user");
        }
    }

    public static class AddTransactionAttribute implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.addTransactionAttribute("an attr", "a val");
        }
    }

    public static class SetTransactionSlowThreshold implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            Glowroot.setTransactionSlowThreshold(Long.MAX_VALUE, MILLISECONDS);
        }
    }
}
