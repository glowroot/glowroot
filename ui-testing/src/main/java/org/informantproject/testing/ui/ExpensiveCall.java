/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.testing.ui;

import java.util.Random;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExpensiveCall {

    private static final Random random = new Random();

    private final int maxTimeMillis;
    private final int maxDescriptionLength;

    public ExpensiveCall(int maxTimeMillis, int maxDescriptionLength) {
        this.maxTimeMillis = maxTimeMillis;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    public void execute() {
        int route = random.nextInt(10);
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
        }
    }

    public void execute0() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute1() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute2() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute3() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute4() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute5() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute6() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute7() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute8() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public void execute9() {
        try {
            Thread.sleep(random.nextInt(maxTimeMillis));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getDescription() {
        int descriptionLength = random.nextInt(maxDescriptionLength);
        StringBuilder sb = new StringBuilder(descriptionLength);
        for (int i = 0; i < descriptionLength; i++) {
            if (random.nextInt(6) == 0) {
                // on average, one of six characters will be a space
                sb.append(' ');
            } else {
                // the rest will be random lowercase characters
                sb.append((char) ('a' + random.nextInt(26)));
            }
        }
        return sb.toString();
    }
}
