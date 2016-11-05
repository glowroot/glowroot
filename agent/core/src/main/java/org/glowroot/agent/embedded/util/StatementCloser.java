/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.embedded.util;

import java.sql.SQLException;
import java.sql.Statement;

import javax.annotation.Nullable;

import com.google.common.base.Throwables;

// similar to guava's Closer, but for java.sql.Statement which doesn't implement Closeable
class StatementCloser {

    private final Statement statement;
    private @Nullable Throwable thrown;

    StatementCloser(Statement statement) {
        this.statement = statement;
    }

    RuntimeException rethrow(Throwable e) throws SQLException {
        thrown = e;
        Throwables.propagateIfPossible(e, SQLException.class);
        throw new RuntimeException(e);
    }

    void close() throws SQLException {
        Throwable throwable = thrown;
        try {
            statement.close();
        } catch (Throwable e) {
            if (throwable == null) {
                throwable = e;
            }
        }
        if (throwable != null) {
            Throwables.propagateIfPossible(throwable, SQLException.class);
            throw new AssertionError(throwable); // not possible
        }
    }
}
