/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc;

import java.sql.Connection;

import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;

import com.sun.gjc.spi.base.ConnectionHolder;

public class GlassfishConnectionHack {

    public static void hack(Connection connection) {
        ((ConnectionHolder) connection).getManagedConnection()
                .addConnectionEventListener(new ConnectionEventListener() {
                    @Override
                    public void connectionClosed(ConnectionEvent event) {}
                    @Override
                    public void localTransactionStarted(ConnectionEvent event) {}
                    @Override
                    public void localTransactionCommitted(ConnectionEvent event) {}
                    @Override
                    public void localTransactionRolledback(ConnectionEvent event) {}
                    @Override
                    public void connectionErrorOccurred(ConnectionEvent event) {}
                });
    }
}
