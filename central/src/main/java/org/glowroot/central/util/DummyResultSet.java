/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.central.util;

import java.util.Iterator;
import java.util.List;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ExecutionInfo;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.util.concurrent.ListenableFuture;

public class DummyResultSet implements ResultSet {

    public static final ResultSet INSTANCE = new DummyResultSet();

    @Override
    public boolean isExhausted() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFullyFetched() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getAvailableWithoutFetching() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<ResultSet> fetchMoreResults() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Row> all() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Row> iterator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionInfo getExecutionInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ExecutionInfo> getAllExecutionInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Row one() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ColumnDefinitions getColumnDefinitions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean wasApplied() {
        throw new UnsupportedOperationException();
    }
}
