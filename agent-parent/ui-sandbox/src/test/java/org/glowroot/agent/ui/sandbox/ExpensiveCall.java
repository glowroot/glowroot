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
package org.glowroot.agent.ui.sandbox;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.ning.http.client.AsyncHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExpensiveCall {

    private static final Logger logger = LoggerFactory.getLogger(ExpensiveCall.class);

    private static final Random random = new Random();

    private static final AsyncHttpClient asyncHttpClient = new AsyncHttpClient();

    private final int maxTimeMillis;
    private final int maxTraceEntryMessageLength;

    ExpensiveCall(int maxTimeMillis, int maxTraceEntryMessageLength) {
        this.maxTimeMillis = maxTimeMillis;
        this.maxTraceEntryMessageLength = maxTraceEntryMessageLength;
    }

    void execute() throws Exception {
        int route = random.nextInt(11);
        switch (route) {
            case 0:
                execute0();
                return;
            case 1:
                execute1();
                return;
            case 2:
                execute2();
                return;
            case 3:
                execute3();
                return;
            case 4:
                execute4();
                return;
            case 5:
                execute5();
                return;
            case 6:
                execute6();
                return;
            case 7:
                execute7();
                return;
            case 8:
                execute8();
                return;
            case 9:
                execute9();
                return;
            default:
                new AsyncWrapperCall(maxTimeMillis, maxTraceEntryMessageLength).execute();
        }
    }

    private void execute0() throws InterruptedException {
        expensive();
        execute1();
    }

    private void execute1() throws InterruptedException {
        expensive();
    }

    private void execute2() throws InterruptedException {
        expensive();
        execute3();
    }

    private void execute3() throws InterruptedException {
        expensive();
    }

    private void execute4() throws InterruptedException {
        expensive();
        execute5();
        try {
            asyncHttpClient.prepareGet("http://example.org").execute().get();
        } catch (ExecutionException e) {
            logger.error(e.getMessage(), e);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void execute5() throws InterruptedException {
        expensive();
    }

    private void execute6() throws InterruptedException {
        expensive();
        execute7();
    }

    private void execute7() throws InterruptedException {
        expensive();
    }

    private void execute8() throws InterruptedException {
        expensive();
        execute9();
    }

    private void execute9() throws InterruptedException {
        expensive();
    }

    public String getTraceEntryMessage() {
        return getTraceEntryMessage(random.nextInt(5) > 0);
    }

    // this is just to prevent jvm from optimizing away for the loop below
    public static final AtomicLong dummy = new AtomicLong();

    // need
    private static final Object lock = new Object();

    static {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true).build();
        Executors.newSingleThreadExecutor(threadFactory).submit(new Callable<Void>() {
            @Override
            public Void call() throws InterruptedException {
                // this loop is used to block threads executing expensive() below
                while (true) {
                    synchronized (lock) {
                        Thread.sleep(random.nextInt(10));
                    }
                    Thread.sleep(1);
                }
            }
        });
    }

    private void expensive() throws InterruptedException {
        int millis = random.nextInt(maxTimeMillis) / 4;
        // spend a quarter of the time taxing the cpu and doing memory allocation
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < millis) {
            for (int i = 0; i < 100000; i++) {
                dummy.addAndGet(random.nextInt(1024));
                if (i % 100 == 0) {
                    dummy.addAndGet(new byte[random.nextInt(1024)].length);
                }
            }
        }
        // spend the rest of the time in both blocking and waiting states
        start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 3 * millis) {
            synchronized (lock) {
                Thread.sleep(random.nextInt(10));
                dummy.incrementAndGet();
            }
            Thread.sleep(1);
        }
    }

    private String getTraceEntryMessage(boolean spaces) {
        int traceEntryMessageLength = random.nextInt(maxTraceEntryMessageLength);
        StringBuilder sb = new StringBuilder(traceEntryMessageLength);
        for (int i = 0; i < traceEntryMessageLength; i++) {
            // random lowercase character
            sb.append((char) ('a' + random.nextInt(26)));
            if (spaces && random.nextInt(6) == 0 && i != 0 && i != traceEntryMessageLength - 1) {
                // on average, one of six characters will be a space (but not first or last char)
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
