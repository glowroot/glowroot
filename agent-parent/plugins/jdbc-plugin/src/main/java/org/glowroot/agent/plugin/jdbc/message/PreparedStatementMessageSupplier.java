/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc.message;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Message;
import org.glowroot.agent.plugin.api.MessageSupplier;

public class PreparedStatementMessageSupplier extends MessageSupplier {

    private final String sql;

    // cannot use ImmutableList for parameters since it can contain null elements
    private final @Nullable BindParameterList parameters;

    public PreparedStatementMessageSupplier(String sql, @Nullable BindParameterList parameters) {
        this.sql = sql;
        this.parameters = parameters;
    }

    @Override
    public Message get() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc execution: ");
        sb.append(sql);
        if (parameters != null && !parameters.isEmpty()) {
            appendParameters(sb, parameters);
        }
        return Message.from(sb.toString());
    }

    static void appendParameters(StringBuilder sb, BindParameterList parameters) {
        sb.append(" [");
        boolean first = true;
        for (Object parameter : parameters) {
            if (!first) {
                sb.append(", ");
            }
            if (parameter instanceof String) {
                sb.append("\'");
                sb.append((String) parameter);
                sb.append("\'");
            } else if (parameter == null) {
                sb.append("NULL");
            } else {
                sb.append(String.valueOf(parameter));
            }
            first = false;
        }
        sb.append("]");
    }
}
