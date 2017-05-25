/*
 * Copyright 2017 the original author or authors.
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

import java.util.Arrays;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class Main {

    private static volatile @MonotonicNonNull CentralModule centralModule;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            start();
            return;
        }
        String command = args[0];
        if (command.equals("create-schema")) {
            if (args.length > 1) {
                System.err.println("unexpected args for create-schema, exiting");
                return;
            }
            CentralModule.createSchema();
        } else {
            CentralModule.runCommand(command, Arrays.asList(args).subList(1, args.length));
        }
    }

    private static void start() throws Exception {
        centralModule = CentralModule.create();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (centralModule != null) {
                    centralModule.shutdown();
                }
            }
        });
    }
}
