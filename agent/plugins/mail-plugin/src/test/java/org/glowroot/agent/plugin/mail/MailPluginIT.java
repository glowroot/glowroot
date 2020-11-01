/**
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.plugin.mail;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.Lists;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class MailPluginIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
    public void shouldSendMessage() throws Exception {
        // when
        Trace trace = container.execute(ExecuteSend.class);

        // then
        checkTimers(trace);

        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).startsWith("mail connect smtp://");

        assertThat(i.hasNext()).isTrue();

        entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("mail send message");

        assertThat(i.hasNext()).isFalse();

    }

    private static void checkTimers(Trace trace) {
        Trace.Timer rootTimer = trace.getHeader().getMainThreadRootTimer();
        List<String> timerNames = Lists.newArrayList();
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            timerNames.add(timer.getName());
        }
        Collections.sort(timerNames);
        assertThat(timerNames).containsExactly("mail");
        for (Trace.Timer timer : rootTimer.getChildTimerList()) {
            assertThat(timer.getChildTimerList()).isEmpty();
        }
        assertThat(trace.getHeader().getAsyncTimerCount()).isZero();
    }

    public static class ExecuteSend extends DoMail {
        @Override
        public void transactionMarker() {
            GreenMailUtil.sendTextEmailTest("to@localhost.com", "from@localhost.com",
                    "some subject", "some body");
        }
    }

    private abstract static class DoMail implements AppUnderTest, TransactionMarker {
        @Override
        public void executeApp() throws Exception {
            GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP);
            try {
                greenMail.start();
                transactionMarker();
            } finally {
                greenMail.stop();
            }
        }
    }
}
