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
package org.informantproject.plugin.jdbc;

import java.lang.ref.WeakReference;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Used by JdbcAspect to capture and mirror the state of statements since the underlying
 * {@link Statement} values cannot be inspected after they have been set.
 * 
 * @author Trask Stalnaker
 * @see java.sql.Statement#addBatch(String)
 * @since 0.5
 */
class StatementMirror {

    // this doesn't apply to PreparedStatementMirror
    private Collection<String> batchedSql;

    // the lastJdbcSpanDetail is stored so that its numRows field
    // can be incremented inside the advice for ResultSet.next()
    //
    // PreparedStatementMirror objects are cached as long as the application
    // server caches the PreparedStatement
    // so a weak reference is used here to allow the JdbcSpan to be collected
    // once it is out of scope
    // (and no longer strongly referenced via the current trace)
    // TODO clear this immediately on Statement.close()?
    private WeakReference<JdbcSpanDetail> lastJdbcSpanDetail;

    void addBatch(String sql) {
        // synchronization isn't an issue here as this method is called only by
        // the monitored thread
        if (batchedSql == null) {
            batchedSql = new ConcurrentLinkedQueue<String>();
        }
        batchedSql.add(sql);
    }

    // just in case someone executes a batch statement and then adds more batches (on top of
    // previous ones) and re-executes (is this possible? TODO write a test case for this)
    Collection<String> getBatchedSqlCopy() {
        return new ArrayList<String>(batchedSql);
    }

    JdbcSpanDetail getLastJdbcSpanDetail() {
        if (lastJdbcSpanDetail == null) {
            return null;
        } else {
            return lastJdbcSpanDetail.get();
        }
    }

    void clearBatch() {
        batchedSql.clear();
    }

    void setLastJdbcSpanDetail(JdbcSpanDetail jdbcSpanDetail) {
        this.lastJdbcSpanDetail = new WeakReference<JdbcSpanDetail>(jdbcSpanDetail);
    }
}
