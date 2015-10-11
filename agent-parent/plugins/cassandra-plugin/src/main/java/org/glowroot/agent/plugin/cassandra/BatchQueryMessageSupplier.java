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
package org.glowroot.agent.plugin.cassandra;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.transaction.Message;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.cassandra.SessionAspect.BatchStatement;
import org.glowroot.agent.plugin.cassandra.SessionAspect.BoundStatement;
import org.glowroot.agent.plugin.cassandra.SessionAspect.PreparedStatement;
import org.glowroot.agent.plugin.cassandra.SessionAspect.RegularStatement;
import org.glowroot.agent.plugin.cassandra.SessionAspect.Statement;

class BatchQueryMessageSupplier extends MessageSupplier {

    private final List<String> queries;

    static BatchQueryMessageSupplier from(Collection<Statement> statements) {
        List<String> queries = new ArrayList<String>();
        String currQuery = null;
        int currCount = 0;
        for (Statement statement : statements) {
            String query = getQuery(statement);
            if (currQuery == null) {
                currQuery = query;
                currCount = 1;
            } else if (!query.equals(currQuery)) {
                if (currCount == 1) {
                    queries.add(currQuery);
                } else {
                    queries.add(currCount + " x " + currQuery);
                }
                currQuery = query;
                currCount = 1;
            } else {
                currCount++;
            }
        }
        if (currQuery != null) {
            if (currCount == 1) {
                queries.add(currQuery);
            } else {
                queries.add(currCount + " x " + currQuery);
            }
        }
        return new BatchQueryMessageSupplier(queries);
    }

    private BatchQueryMessageSupplier(List<String> queries) {
        super();
        this.queries = queries;
    }

    @Override
    public Message get() {
        StringBuilder sb = new StringBuilder();
        sb.append("cql execution: ");
        for (int i = 0; i < queries.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(queries.get(i));
        }
        return Message.from(sb.toString());
    }

    private static String getQuery(Statement statement) {
        if (statement instanceof RegularStatement) {
            String qs = ((RegularStatement) statement).getQueryString();
            return nullToEmpty(qs);
        } else if (statement instanceof BoundStatement) {
            PreparedStatement preparedStatement = ((BoundStatement) statement).preparedStatement();
            String qs = preparedStatement == null ? "" : preparedStatement.getQueryString();
            return nullToEmpty(qs);
        } else if (statement instanceof BatchStatement) {
            return "[nested batch statement]";
        } else {
            return "[unexpected statement type: " + statement.getClass().getName() + "]";
        }
    }

    private static String nullToEmpty(@Nullable String string) {
        return string == null ? "" : string;
    }
}
