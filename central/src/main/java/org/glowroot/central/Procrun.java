/*
 * Copyright 2015-2023 the original author or authors.
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
package org.glowroot.central;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

// this class is for use with apache commons daemon on windows
// see http://commons.apache.org/proper/commons-daemon/procrun.html
public class Procrun {

    private static volatile @MonotonicNonNull CentralModule centralModule;

    private Procrun() {}

    public static void main(String[] args) throws Exception {
        if (args[0].equals("start")) {
            centralModule = CentralModule.create();
        } else if (args[0].equals("stop")) {
            if (centralModule == null) {
                // has not finished starting up
                System.exit(0);
            } else {
                centralModule.shutdown(true);
                // in case there are any stray non-daemon threads
                System.exit(0);
            }
        } else {
            throw new IllegalStateException("Unexpected arg: " + args[0]);
        }
    }
}
