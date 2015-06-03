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
package org.glowroot.container.impl;


// this class is used (for better or worse) by a couple of tests, and is needed so that those tests
// do not depend directly on glowroot-core classes which causes an issue during mvn clean install
public class SpyingLogbackFilter {

    public static boolean active() {
        return org.glowroot.common.SpyingLogbackFilter.active();
    }

    public static void clearMessages() {
        org.glowroot.common.SpyingLogbackFilter.clearMessages();
    }
}
