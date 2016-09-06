/**
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
package org.glowroot.testing;

public enum JavaVersion {

    JAVA6("java6.home"),
    JAVA7("java7.home"),
    JAVA8("java.home");

    private final String javaHome;

    private JavaVersion(String propertyName) {
        String javaHome = System.getProperty(propertyName);
        if (javaHome == null) {
            throw new IllegalStateException("Missing " + propertyName);
        }
        this.javaHome = javaHome;
    }

    public String getJavaHome() {
        return javaHome;
    }
}
