/*
 * Copyright 2013-2017 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveTraceRepository.TraceKind;
import org.glowroot.common.live.LiveTraceRepository.TracePointFilter;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.repo.TraceRepository.TraceQuery;
import org.glowroot.common.util.Styles;

class TracePointQueryBuilder {

    private final TraceKind traceKind;
    private final TraceQuery query;
    private final TracePointFilter filter;
    private final int limit;

    TracePointQueryBuilder(TraceKind traceKind, TraceQuery query, TracePointFilter filter,
            int limit) {
        this.traceKind = traceKind;
        this.query = query;
        this.filter = filter;
        this.limit = limit;
    }

    // capture time lower bound is non-inclusive so that aggregate data intervals can be mapped
    // to their trace points (aggregate data intervals are non-inclusive on lower bound and
    // inclusive on upper bound)
    ParameterizedSql getParameterizedSql() {
        ParameterizedSqlBuilder builder = new ParameterizedSqlBuilder();
        builder.appendText("select trace.id, trace.capture_time, trace.duration_nanos,"
                + " trace.partial, trace.error from trace");
        ParameterizedSql criteria = getAttributeCriteria();
        if (criteria == null) {
            builder.appendText(" where");
        } else {
            builder.appendText(", trace_attribute attr where attr.trace_id = trace.id"
                    + " and attr.capture_time > ? and attr.capture_time <= ? and" + criteria.sql());
            builder.addArg(query.from());
            builder.addArg(query.to());
            builder.addArgs(criteria.args());

        }
        builder.appendText(" trace.capture_time > ? and trace.capture_time <= ?");
        builder.addArg(query.from());
        builder.addArg(query.to());
        appendTraceKindCriteria(builder);
        appendTransactionTypeCriteria(builder);
        appendTransactionNameCriteria(builder);
        appendHeadlineCriteria(builder);
        appendErrorCriteria(builder);
        appendUserCriteria(builder);
        appendOrderByAndLimit(builder);
        return builder.build();
    }

    private @Nullable ParameterizedSql getAttributeCriteria() {
        String sql = "";
        List<Object> args = Lists.newArrayList();
        String attributeName = filter.attributeName();
        if (!Strings.isNullOrEmpty(attributeName)) {
            sql += " upper(attr.name) = ? and";
            args.add(attributeName.toUpperCase(Locale.ENGLISH));
        }
        StringComparator attributeValueComparator = filter.attributeValueComparator();
        String attributeValue = filter.attributeValue();
        if (attributeValueComparator != null && !Strings.isNullOrEmpty(attributeValue)) {
            sql += " upper(attr.value) " + attributeValueComparator.getComparator() + " ? and";
            args.add(attributeValueComparator.formatParameter(attributeValue));
        }
        if (sql.isEmpty()) {
            return null;
        } else {
            return ImmutableParameterizedSql.of(sql, args);
        }
    }

    private void appendTraceKindCriteria(ParameterizedSqlBuilder builder) {
        if (traceKind == TraceKind.SLOW) {
            builder.appendText(" and trace.slow = ?");
            builder.addArg(true);
        } else {
            // TraceKind.ERROR
            builder.appendText(" and trace.error = ?");
            builder.addArg(true);
        }
    }

    private void appendTransactionTypeCriteria(ParameterizedSqlBuilder builder) {
        builder.appendText(" and trace.transaction_type = ?");
        builder.addArg(query.transactionType());
    }

    private void appendTransactionNameCriteria(ParameterizedSqlBuilder builder) {
        String transactionName = query.transactionName();
        if (transactionName != null) {
            builder.appendText(" and trace.transaction_name = ?");
            builder.addArg(transactionName);
        }
    }

    private void appendHeadlineCriteria(ParameterizedSqlBuilder builder) {
        StringComparator headlineComparator = filter.headlineComparator();
        String headline = filter.headline();
        if (headlineComparator != null && !Strings.isNullOrEmpty(headline)) {
            builder.appendText(
                    " and upper(trace.headline) " + headlineComparator.getComparator() + " ?");
            builder.addArg(headlineComparator.formatParameter(headline));
        }
    }

    private void appendErrorCriteria(ParameterizedSqlBuilder builder) {
        StringComparator errorComparator = filter.errorMessageComparator();
        String error = filter.errorMessage();
        if (errorComparator != null && !Strings.isNullOrEmpty(error)) {
            builder.appendText(
                    " and upper(trace.error_message) " + errorComparator.getComparator() + " ?");
            builder.addArg(errorComparator.formatParameter(error));
        }
    }

    private void appendUserCriteria(ParameterizedSqlBuilder builder) {
        StringComparator userComparator = filter.userComparator();
        String user = filter.user();
        if (userComparator != null && !Strings.isNullOrEmpty(user)) {
            builder.appendText(" and upper(trace.user) " + userComparator.getComparator() + " ?");
            builder.addArg(userComparator.formatParameter(user));
        }
    }

    private void appendOrderByAndLimit(ParameterizedSqlBuilder builder) {
        builder.appendText(" order by trace.duration_nanos");
        if (limit != 0) {
            // +1 is to identify if limit was exceeded
            builder.appendText(" desc limit ?");
            builder.addArg(limit + 1);
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    abstract static class ParameterizedSql {

        abstract @Untainted String sql();
        abstract ImmutableList<Object> args();
    }

    private static class ParameterizedSqlBuilder {

        private @Untainted String sql = "";
        private final List<Object> args = Lists.newArrayList();

        private void appendText(@Untainted String sql) {
            this.sql += sql;
        }

        public void addArg(Object arg) {
            args.add(arg);
        }

        public void addArgs(List<Object> args) {
            this.args.addAll(args);
        }

        private ParameterizedSql build() {
            return ImmutableParameterizedSql.of(sql, args);
        }
    }
}
