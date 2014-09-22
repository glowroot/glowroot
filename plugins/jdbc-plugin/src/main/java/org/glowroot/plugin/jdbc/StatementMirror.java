/*
 * Copyright 2011-2014 the original author or authors.
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

import java.sql.Statement;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Used to capture and mirror the state of statements since the underlying {@link Statement} values
 * cannot be inspected after they have been set.
 * 
 * @author Trask Stalnaker
 * @see java.sql.Statement#addBatch(String)
 * @since 0.5
 */
class StatementMirror {

    // this field is not used by PreparedStatementMirror subclass
    //
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is adding batches into the statement and executing it
    @Nullable
    private List<String> batchedSql;

    // the lastRecordCountObject is stored so that its numRows field can be incremented inside the
    // advice for ResultSet.next()
    //
    // PreparedStatementMirror objects are cached as long as the application server caches the
    // PreparedStatement, so storing small RecordCountObject instead of JdbcMessageSupplier
    // in order to avoid using WeakReference
    //
    // to help out gc a little, JdbcAspect clears lastRecordCountObject on Statement.close(), but
    // can't solely rely on this in case a jdbc driver implementation closes statements in finalize
    // by calling an internal method and not calling public close()
    //
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is adding batches into the statement and executing it
    @Nullable
    private RecordCountObject lastRecordCountObject;

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
    RecordCountObject getLastRecordCountObject() {
        return lastRecordCountObject;
    }

    void clearBatch() {
        if (batchedSql != null) {
            batchedSql.clear();
        }
    }

    void setLastRecordCountObject(RecordCountObject recordCountObject) {
        this.lastRecordCountObject = recordCountObject;
    }

    void clearLastRecordCountObject() {
        lastRecordCountObject = null;
    }
}
