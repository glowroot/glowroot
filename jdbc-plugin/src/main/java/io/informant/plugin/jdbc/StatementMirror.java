/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.plugin.jdbc;

import java.lang.ref.WeakReference;
import java.sql.Statement;
import java.util.Collection;

import checkers.nullness.quals.Nullable;

import io.informant.shaded.google.common.collect.ImmutableList;
import io.informant.shaded.google.common.collect.Queues;

/**
 * Used by JdbcAspect to capture and mirror the state of statements since the underlying
 * {@link Statement} values cannot be inspected after they have been set.
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
    private Collection<String> batchedSql;

    // the lastJdbcMessageSupplier is stored so that its numRows field can be incremented inside the
    // advice for ResultSet.next()
    //
    // PreparedStatementMirror objects are cached as long as the application server caches the
    // PreparedStatement, so a weak reference is used here to allow the JdbcMessageSupplier to be
    // collected once it is out of scope (and no longer strongly referenced via the current trace)
    //
    // to help out gc a little, JdbcAspect clears lastJdbcMessageSupplier on Statement.close(), but
    // can't solely rely on this (and use strong reference) in case a jdbc driver implementation
    // closes statements in finalize by calling an internal method and not calling public close()
    //
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is adding batches into the statement and executing it
    @Nullable
    private WeakReference<JdbcMessageSupplier> lastJdbcMessageSupplier;

    void addBatch(String sql) {
        // synchronization isn't an issue here as this method is called only by
        // the monitored thread
        if (batchedSql == null) {
            batchedSql = Queues.newConcurrentLinkedQueue();
        }
        batchedSql.add(sql);
    }

    // just in case someone executes a batch statement and then adds more batches (on top of
    // previous ones) and re-executes (is this possible? TODO write a test case for this)
    ImmutableList<String> getBatchedSqlCopy() {
        if (batchedSql == null) {
            return ImmutableList.of();
        } else {
            return ImmutableList.copyOf(batchedSql);
        }
    }

    @Nullable
    JdbcMessageSupplier getLastJdbcMessageSupplier() {
        if (lastJdbcMessageSupplier == null) {
            return null;
        } else {
            return lastJdbcMessageSupplier.get();
        }
    }

    void clearBatch() {
        if (batchedSql != null) {
            batchedSql.clear();
        }
    }

    void setLastJdbcMessageSupplier(@Nullable JdbcMessageSupplier jdbcMessageSupplier) {
        if (jdbcMessageSupplier == null) {
            lastJdbcMessageSupplier = null;
        } else {
            lastJdbcMessageSupplier = new WeakReference<JdbcMessageSupplier>(jdbcMessageSupplier);
        }
    }
}
