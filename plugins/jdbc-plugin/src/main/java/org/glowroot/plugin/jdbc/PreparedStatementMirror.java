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

import java.util.Collection;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Queues;
import com.google.common.hash.HashCode;

// used to capture and mirror the state of prepared statements since the underlying
// PreparedStatement values cannot be inspected after they have been set
class PreparedStatementMirror extends StatementMirror {

    private static final int PARAMETERS_INITIAL_CAPACITY = 20;

    private final String sql;
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is setting parameter values into the prepared statement and executing it
    private BindParameterList parameters;
    private boolean parametersCopied;
    // ok for this field to be non-volatile since it is only temporary storage for a single thread
    // while that thread is setting parameter values into the prepared statement and executing it
    @Nullable
    private Collection<BindParameterList> batchedParameters;

    public PreparedStatementMirror(String sql) {
        this.sql = sql;
        parameters = new BindParameterList(PARAMETERS_INITIAL_CAPACITY);
    }

    public void addBatch() {
        // synchronization isn't an issue here as this method is called only by the monitored thread
        if (batchedParameters == null) {
            batchedParameters = Queues.newConcurrentLinkedQueue();
        }
        batchedParameters.add(parameters);
        parameters = new BindParameterList(parameters.size());
    }

    public ImmutableList<BindParameterList> getBatchedParametersCopy() {
        if (batchedParameters == null) {
            return ImmutableList.of();
        } else {
            // batched parameters cannot be changed after calling addBatch(),
            // so it is safe to not copy the inner list
            return ImmutableList.copyOf(batchedParameters);
        }
    }

    @Nullable
    public BindParameterList getParametersCopy() {
        parametersCopied = true;
        return parameters;
    }

    public String getSql() {
        return sql;
    }

    // remember parameterIndex starts at 1 not 0
    public void setParameterValue(int parameterIndex, Object object) {
        if (parametersCopied) {
            parameters = BindParameterList.copyOf(parameters);
            parametersCopied = false;
        }
        parameters.set(parameterIndex - 1, object);
    }

    @Override
    public void clearBatch() {
        if (parametersCopied) {
            parameters = new BindParameterList(parameters.size());
            parametersCopied = false;
        } else {
            parameters.clear();
        }
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
                return "0x" + HashCode.fromBytes(bytes).toString();
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
