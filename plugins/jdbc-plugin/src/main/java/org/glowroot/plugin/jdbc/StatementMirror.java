/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.plugin.jdbc;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.plugin.jdbc.message.JdbcMessageSupplier;

// used to capture and mirror the state of statements since the underlying {@link Statement} values
// cannot be inspected after they have been set
class StatementMirror {

    // this field is not used by PreparedStatementMirror subclass
    //
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is adding batches into the statement and executing it
    private @Nullable List<String> batchedSql;

    // the jdbcMessageSupplier is stored so that its numRows field can be incremented inside the
    // advice for ResultSet.next()
    //
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is adding batches into the statement and executing it
    private @Nullable JdbcMessageSupplier lastJdbcMessageSupplier;

    void addBatch(String sql) {
        // synchronization isn't an issue here as this method is called only by
        // the monitored thread
        if (batchedSql == null) {
            batchedSql = Lists.newArrayList();
        }
        batchedSql.add(sql);
    }

    ImmutableList<String> getBatchedSqlCopy() {
        if (batchedSql == null) {
            return ImmutableList.of();
        } else {
            return ImmutableList.copyOf(batchedSql);
        }
    }

    @Nullable
    JdbcMessageSupplier getLastJdbcMessageSupplier() {
        return lastJdbcMessageSupplier;
    }

    void clearBatch() {
        if (batchedSql != null) {
            batchedSql.clear();
        }
    }

    void setLastJdbcMessageSupplier(JdbcMessageSupplier lastJdbcMessageSupplier) {
        this.lastJdbcMessageSupplier = lastJdbcMessageSupplier;
    }

    void clearLastJdbcMessageSupplier() {
        lastJdbcMessageSupplier = null;
    }
}
