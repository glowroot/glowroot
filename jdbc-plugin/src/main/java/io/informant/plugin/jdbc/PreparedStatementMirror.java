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

import io.informant.shaded.google.common.collect.ImmutableList;
import io.informant.shaded.google.common.collect.Lists;
import io.informant.shaded.google.common.collect.Queues;
import io.informant.shaded.google.common.hash.HashCodes;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.List;

import checkers.nullness.quals.Nullable;

/**
 * Used by JdbcAspect to capture and mirror the state of prepared statements since the underlying
 * {@link PreparedStatement} values cannot be inspected after they have been set.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class PreparedStatementMirror extends StatementMirror {

    private final String sql;
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is setting parameter values into the prepared statement and executing it
    private List</*@Nullable*/Object> parameters;
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is setting parameter values into the prepared statement and executing it
    @Nullable
    private Collection<List</*@Nullable*/Object>> batchedParameters;

    public PreparedStatementMirror(String sql) {
        this.sql = sql;
        parameters = Lists.newArrayList();
    }

    public void addBatch() {
        // synchronization isn't an issue here as this method is called only by the monitored thread
        if (batchedParameters == null) {
            batchedParameters = Queues.newConcurrentLinkedQueue();
        }
        batchedParameters.add(parameters);
        parameters = Lists.newArrayListWithCapacity(parameters.size());
    }

    public ImmutableList<List</*@Nullable*/Object>> getBatchedParametersCopy() {
        if (batchedParameters == null) {
            return ImmutableList.of();
        } else {
            // batched parameters cannot be changed after calling addBatch(),
            // so it is safe to not copy the inner list
            return ImmutableList.copyOf(batchedParameters);
        }
    }

    public boolean isUsingBatchedParameters() {
        return batchedParameters != null;
    }

    public List</*@Nullable*/Object> getParametersCopy() {
        // cannot return ImmutableList.copyOf() since ImmutableList does not allow null elements
        return Lists.newArrayList(parameters);
    }

    public String getSql() {
        return sql;
    }

    // remember parameterIndex starts at 1 not 0
    public void setParameterValue(int parameterIndex, Object object) {
        if (parameterIndex == parameters.size() + 1) {
            // common path
            parameters.add(object);
        } else if (parameterIndex < parameters.size() + 1) {
            // overwrite existing value
            parameters.set(parameterIndex - 1, object);
        } else {
            // expand list with nulls
            for (int i = parameters.size() + 1; i < parameterIndex; i++) {
                parameters.add(null);
            }
            parameters.add(object);
        }
    }

    @Override
    public void clearBatch() {
        parameters.clear();
        if (batchedParameters != null) {
            batchedParameters.clear();
        }
    }

    static class NullParameterValue {
        @Override
        public String toString() {
            return "NULL";
        }
    }

    static class ByteArrayParameterValue {
        private final int length;
        private final byte/*@Nullable*/[] bytes;
        public ByteArrayParameterValue(byte[] bytes, boolean displayAsHex) {
            length = bytes.length;
            if (displayAsHex) {
                // only retain bytes if needed for displaying as hex
                this.bytes = bytes;
            } else {
                this.bytes = null;
            }
        }
        @Override
        public String toString() {
            if (bytes != null) {
                return "0x" + HashCodes.fromBytes(bytes).toString();
            } else {
                return "{" + length + " bytes}";
            }
        }
    }

    static class StreamingParameterValue {
        private final Object o;
        public StreamingParameterValue(Object o) {
            this.o = o;
        }
        @Override
        public String toString() {
            return "{stream:" + o.getClass().getSimpleName() + "}";
        }
    }
}
