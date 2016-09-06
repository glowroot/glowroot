/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

class InitLogging {

    static {
        try {
            Class.forName("org.slf4j.bridge.SLF4JBridgeHandler")
                    .getMethod("removeHandlersForRootLogger").invoke(null);
            Class.forName("org.slf4j.bridge.SLF4JBridgeHandler")
                    .getMethod("install").invoke(null);
        } catch (ClassNotFoundException e) {
            // this is needed when running logger plugin tests against old logback versions
        } catch (NoSuchMethodException e) {
            // this is needed when running logger plugin tests against old logback versions
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private InitLogging() {}
}
