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

import java.util.Collection;
import java.util.List;

import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;

/**
 * Jdbc span captured by AspectJ pointcut.
 * 
 * Objects in the parameter array, batchedParameters collections and batchedSqls collection aren't
 * necessarily thread safe so users of this class must adhere to the following contract:
 * 
 * 1. The arrays / collections should not be modified after construction.
 * 
 * 2. None of the elements in these arrays / collections should be modified after construction.
 * 
 * 3. There should be some kind of coordination between the threads to ensure visibility of the
 * objects in these arrays / collections. In our case, one thread is putting the JdbcSpanDetail on
 * to a concurrent queue (right after construction) and the only way another thread can get access
 * to it is by pulling it off of the queue. The concurrent queue stores the instance in a volatile
 * field and so the coordination of the first thread writing to that volatile field and the second
 * thread reading from that volatile field ensures a happens-before relationship which guarantees
 * that the second thread sees everything that was done to the objects in the parameters array prior
 * to the first thread putting the span into the concurrent queue.
 * 
 * hasPerformedNext and numRows are marked volatile to ensure visibility to other threads since they
 * are updated after putting the JdbcSpanDetail on to the queue and so these updates cannot
 * piggyback on the happens-before relationship created by queuing / dequeuing.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class JdbcSpanDetail implements SpanDetail {

    static final Object NULL_PARAMETER = new Object();

    private final String sql;

    // parameters and batchedParameters cannot both be non-null
    private final List<Object> parameters;
    private final Collection<List<Object>> batchedParameters;

    // this is only used for batching of non-PreparedStatements
    private final Collection<String> batchedSqls;

    private volatile boolean hasPerformedNext;
    private volatile int numRows;

    JdbcSpanDetail(String sql) {
        this.sql = sql;
        this.parameters = null;
        this.batchedParameters = null;
        this.batchedSqls = null;
    }

    JdbcSpanDetail(String sql, List<Object> parameters) {
        this.sql = sql;
        this.parameters = parameters;
        this.batchedParameters = null;
        this.batchedSqls = null;
    }

    JdbcSpanDetail(Collection<String> batchedSqls) {
        this.sql = null;
        this.parameters = null;
        this.batchedParameters = null;
        this.batchedSqls = batchedSqls;
    }

    JdbcSpanDetail(String sql, Collection<List<Object>> batchedParameters) {
        this.sql = sql;
        this.parameters = null;
        this.batchedParameters = batchedParameters;
        this.batchedSqls = null;
    }

    public String getDescription() {
        // initialized to 128 characters since sql texts are typically fairly long
        StringBuffer description = new StringBuffer(128);
        description.append("jdbc execution: ");
        if (batchedSqls != null) {
            appendBatchedSqls(description);
            return description.toString();
        }
        if (isUsingBatchedParameters() && batchedParameters.size() > 1) {
            // print out number of batches to make it easy to identify
            description.append(batchedParameters.size());
            description.append(" x ");
        }
        description.append(sql);
        if (isUsingParameters() && !parameters.isEmpty()) {
            appendParameters(description, parameters);
        } else if (isUsingBatchedParameters()) {
            for (List<Object> oneParameters : batchedParameters) {
                appendParameters(description, oneParameters);
            }
        }
        if (hasPerformedNext) {
            appendRowCount(description);
        }
        return description.toString();
    }

    // TODO put row num and bind parameters in context map?
    public SpanContextMap getContextMap() {
        return null;
    }

    void setHasPerformedNext() {
        hasPerformedNext = true;
    }

    void setNumRows(int numRows) {
        this.numRows = numRows;
    }

    private boolean isUsingParameters() {
        return parameters != null;
    }

    private boolean isUsingBatchedParameters() {
        return batchedParameters != null;
    }

    private void appendBatchedSqls(StringBuffer description) {
        boolean first = true;
        for (String batchedSql : batchedSqls) {
            if (!first) {
                description.append(", ");
            }
            description.append(batchedSql);
            first = false;
        }
    }

    private void appendRowCount(StringBuffer description) {
        description.append(" => ");
        description.append(numRows);
        if (numRows == 1) {
            description.append(" row");
        } else {
            description.append(" rows");
        }
    }

    private static void appendParameters(StringBuffer description, List<Object> parameters) {
        description.append(" [");
        boolean first = true;
        for (Object parameter : parameters) {
            if (!first) {
                description.append(", ");
            }
            if (parameter instanceof String) {
                description.append('\'');
                description.append(parameter);
                description.append('\'');
            } else if (parameter == NULL_PARAMETER) {
                description.append("NULL");
            } else {
                description.append(parameter);
            }
            first = false;
        }
        description.append(']');
    }
}
