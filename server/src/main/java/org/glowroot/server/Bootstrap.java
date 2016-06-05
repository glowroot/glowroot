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
package org.glowroot.server;

import javax.annotation.Nullable;

// this class is designed to be used with apache commons daemon
// http://commons.apache.org/proper/commons-daemon/procrun.html
public class Bootstrap {

    private static volatile @Nullable ServerModule serverModule;

    private Bootstrap() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("start")) {
            serverModule = new ServerModule();
        } else if (args[0].equals("stop")) {
            if (serverModule != null) {
                serverModule.close();
            }
        } else {
            throw new IllegalStateException("Unexpected arg: " + args[0]);
        }
    }
}
