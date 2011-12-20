/**
 * Copyright 2011 the original author or authors.
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * Convenience method to verify whether a table exists in a database. A little strange maybe that
 * this is so useful.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JdbcHelper {

    private static final Logger logger = LoggerFactory.getLogger(JdbcHelper.class);

    private final Connection connection;

    @Inject
    JdbcHelper(Connection connection) {
        this.connection = connection;
    }

    public boolean tableExists(String tableName) throws SQLException {
        logger.debug("tableExists(): tableName={}", tableName);
        ResultSet resultSet = connection.getMetaData().getTables(null, null,
                tableName.toUpperCase(Locale.ENGLISH), null);
        try {
            return resultSet.next();
        } finally {
            resultSet.close();
        }
    }
}
