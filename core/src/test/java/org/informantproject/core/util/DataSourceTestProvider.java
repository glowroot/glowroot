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
package org.informantproject.core.util;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import com.google.inject.Provider;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataSourceTestProvider implements Provider<DataSource> {

    public DataSource get() {
        File dbFile;
        try {
            dbFile = File.createTempFile("informant-test-", ".h2.db");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        DataSource dataSource = new DataSource(dbFile);
        try {
            if (dataSource.tableExists("trace")) {
                dataSource.execute("drop table trace");
            }
            if (dataSource.tableExists("config")) {
                dataSource.execute("drop table config");
            }
            return dataSource;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
