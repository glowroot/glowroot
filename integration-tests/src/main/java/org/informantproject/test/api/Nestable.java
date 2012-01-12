/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.test.api;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Nestable {

    private final Nestable child;
    private final int[] sleepTimings;

    public Nestable() {
        this(null, new int[0]);
    }

    public Nestable(Nestable child) {
        this(child, new int[0]);
    }

    public Nestable(int[] sleepTimings) {
        this(null, sleepTimings);
    }

    public Nestable(Nestable child, int[] sleepTimings) {
        if (sleepTimings.length > 10) {
            throw new IllegalArgumentException("Only up to 10 sleep timings currently supported");
        }
        this.child = child;
        this.sleepTimings = sleepTimings;
    }

    public void call() throws InterruptedException {
        if (child != null) {
            child.call();
        }
        // the calls to Thread.sleep() are spread out over different line numbers (as opposed to
        // using a loop) so that stack trace captures across the different sleep calls will not be
        // identical
        if (sleepTimings.length > 0) {
            Thread.sleep(sleepTimings[0]);
        }
        if (sleepTimings.length > 1) {
            Thread.sleep(sleepTimings[1]);
        }
        if (sleepTimings.length > 2) {
            Thread.sleep(sleepTimings[2]);
        }
        if (sleepTimings.length > 3) {
            Thread.sleep(sleepTimings[3]);
        }
        if (sleepTimings.length > 4) {
            Thread.sleep(sleepTimings[4]);
        }
        if (sleepTimings.length > 5) {
            Thread.sleep(sleepTimings[5]);
        }
        if (sleepTimings.length > 6) {
            Thread.sleep(sleepTimings[6]);
        }
        if (sleepTimings.length > 7) {
            Thread.sleep(sleepTimings[7]);
        }
        if (sleepTimings.length > 8) {
            Thread.sleep(sleepTimings[8]);
        }
        if (sleepTimings.length > 9) {
            Thread.sleep(sleepTimings[9]);
        }
    }
}
