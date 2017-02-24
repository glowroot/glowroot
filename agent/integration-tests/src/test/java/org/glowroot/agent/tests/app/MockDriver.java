/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.agent.tests.app;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.util.Properties;

public class MockDriver implements Driver {

    static {
        MockDriverState.setLoaded();
        new Exception().printStackTrace();
    }

    @Override
    public Connection connect(String url, Properties info) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean acceptsURL(String url) {
        return false;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    // new java.sql.Driver method in jdk7
    public java.util.logging.Logger getParentLogger() {
        throw new UnsupportedOperationException();
    }
}
