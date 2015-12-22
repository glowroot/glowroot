/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.central.storage;

import java.util.List;
import java.util.Map;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import org.glowroot.storage.repo.TransactionTypeRepository;

import static com.google.common.base.Preconditions.checkNotNull;

public class TransactionTypeDao implements TransactionTypeRepository {

    private final Session session;

    private final PreparedStatement insertPS;

    public TransactionTypeDao(Session session) {
        this.session = session;

        session.execute("create table if not exists transaction_type (one int,"
                + " server_rollup varchar, transaction_type varchar, primary key"
                + " (one, server_rollup, transaction_type))");

        insertPS = session.prepare("insert into transaction_type (one, server_rollup,"
                + " transaction_type) values (1, ?, ?)");
    }

    @Override
    public Map<String, List<String>> readTransactionTypes() {
        ResultSet results = session.execute(
                "select server_rollup, transaction_type from transaction_type where one = 1");

        ImmutableMap.Builder<String, List<String>> builder = ImmutableMap.builder();
        String currServerRollup = null;
        List<String> currTransactionTypes = Lists.newArrayList();
        for (Row row : results) {
            String serverRollup = checkNotNull(row.getString(0));
            String transactionType = checkNotNull(row.getString(1));
            if (currServerRollup == null) {
                currServerRollup = serverRollup;
            }
            if (!serverRollup.equals(currServerRollup)) {
                builder.put(currServerRollup, ImmutableList.copyOf(currTransactionTypes));
                currServerRollup = serverRollup;
                currTransactionTypes = Lists.newArrayList();
            }
            currTransactionTypes.add(transactionType);
        }
        if (currServerRollup != null) {
            builder.put(currServerRollup, ImmutableList.copyOf(currTransactionTypes));
        }
        return builder.build();
    }

    @Override
    public void deleteAll(String serverRollup) throws Exception {
        // this is not currently supported (to avoid row key range query)
        throw new UnsupportedOperationException();
    }

    void updateLastCaptureTime(String serverRollup, String transactionType) {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, serverRollup);
        boundStatement.setString(1, transactionType);
        session.execute(boundStatement);
    }
}
