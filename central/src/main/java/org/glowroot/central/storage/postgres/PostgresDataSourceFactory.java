/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import javax.sql.DataSource;

import com.google.common.base.Strings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class PostgresDataSourceFactory {

    private PostgresDataSourceFactory() {}

    public static HikariDataSource create(String jdbcUrl, String username, String password,
            int maximumPoolSize) {
        if (Strings.isNullOrEmpty(jdbcUrl)) {
            throw new IllegalStateException(
                    "postgres.jdbcUrl must be set when storage.type=postgres");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (!Strings.isNullOrEmpty(username)) {
            config.setUsername(username);
        }
        if (!Strings.isNullOrEmpty(password)) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(Math.max(1, maximumPoolSize));
        config.setPoolName("glowroot-central-postgres");
        return new HikariDataSource(config);
    }

    public static void closeQuietly(/*@Nullable*/ DataSource dataSource) {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }
}
