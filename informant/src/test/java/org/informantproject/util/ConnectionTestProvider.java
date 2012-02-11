/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.google.inject.Provider;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ConnectionTestProvider implements Provider<Connection> {

    public Connection get() {
        try {
            try {
                Class.forName("org.h2.Driver");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            Connection connection = DriverManager.getConnection(
                    "jdbc:h2:informant;COMPRESS_LOB=LZF", "sa", "");
            // cyclic relationship here between connection and jdbc helper
            if (JdbcUtil.tableExists("trace", connection)) {
                Statement statement = connection.createStatement();
                statement.execute("drop table trace");
                statement.close();
            }
            if (JdbcUtil.tableExists("configuration", connection)) {
                Statement statement = connection.createStatement();
                statement.execute("drop table configuration");
                statement.close();
            }
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
