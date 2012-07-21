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
package org.informantproject.plugin.jdbc;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.informantproject.shaded.google.common.collect.ImmutableList;
import org.informantproject.shaded.google.common.collect.Lists;

/**
 * Used by JdbcAspect to capture and mirror the state of prepared statements since the underlying
 * {@link PreparedStatement} values cannot be inspected after they have been set.
 * 
 * TODO does this need to be thread safe? Need to research JDBC spec, can one thread create a
 * PreparedStatement and set some parameters into it, and then have another thread execute it (with
 * those previously set parameters), if there is nothing that says no, then may need to make this
 * thread safe
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class PreparedStatementMirror extends StatementMirror {

    private static final char[] hexDigits = "0123456789abcdef".toCharArray();

    private final String sql;
    private List<Object> parameters;
    @Nullable
    private Collection<List<Object>> batchedParameters;

    public PreparedStatementMirror(String sql) {
        this.sql = sql;
        parameters = Lists.newArrayList();
    }

    public void addBatch() {
        // synchronization isn't an issue here as this method is called only by the monitored thread
        if (batchedParameters == null) {
            batchedParameters = new ConcurrentLinkedQueue<List<Object>>();
        }
        batchedParameters.add(parameters);
        parameters = Lists.newArrayListWithCapacity(parameters.size());
    }

    public Collection<List<Object>> getBatchedParametersCopy() {
        if (batchedParameters == null) {
            return ImmutableList.of();
        } else {
            // batched parameters cannot be changed after calling addBatch(),
            // so it is safe to not copy the inner list
            // cannot return ImmutableList.copyOf() since ImmutableList does not allow null elements
            return Lists.newArrayList(batchedParameters);
        }
    }

    public boolean isUsingBatchedParameters() {
        return batchedParameters != null;
    }

    public List<Object> getParametersCopy() {
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
        @Nullable
        private final byte[] bytes;
        private final boolean displayAsHex;
        public ByteArrayParameterValue(byte[] bytes, boolean displayAsHex) {
            length = bytes.length;
            this.displayAsHex = displayAsHex;
            if (displayAsHex) {
                // only retain bytes if needed for displaying as hex
                this.bytes = bytes;
            } else {
                this.bytes = null;
            }
        }
        @Override
        public String toString() {
            if (displayAsHex) {
                return toHex(bytes);
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

    // copied from com.google.common.hash.HashCode.toString() with minor modification
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 + 2 * bytes.length);
        sb.append("0x");
        for (byte b : bytes) {
            sb.append(hexDigits[(b >> 4) & 0xf]).append(hexDigits[b & 0xf]);
        }
        return sb.toString();
    }
}
