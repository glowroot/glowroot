/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.local;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.common.repo.StringComparator;
import org.glowroot.common.repo.TraceRepository.TracePointQuery;
import org.glowroot.common.util.Styles;

public class TracePointQueryBuilder {

    private final TracePointQuery query;

    public TracePointQueryBuilder(TracePointQuery query) {
        this.query = query;
    }

    // capture time lower bound is non-inclusive so that aggregate data intervals can be mapped
    // to their trace points (aggregate data intervals are non-inclusive on lower bound and
    // inclusive on upper bound)
    public ParameterizedSql getParameterizedSql() {
        ParameterizedSqlBuilder builder = new ParameterizedSqlBuilder();
        builder.appendText("select trace.id, trace.capture_time, trace.duration_nanos, trace.error"
                + " from trace");
        ParameterizedSql criteria = getCustomAttributeCriteria();
        if (criteria == null) {
            builder.appendText(" where");
        } else {
            builder.appendText(", trace_custom_attribute attr where attr.trace_id = trace.id and"
                    + " attr.capture_time > ? and attr.capture_time <= ? and" + criteria.sql());
            builder.addArg(query.from());
            builder.addArg(query.to());
            builder.addArgs(criteria.args());

        }
        builder.appendText(" trace.capture_time > ? and trace.capture_time <= ?");
        builder.addArg(query.from());
        builder.addArg(query.to());
        appendDurationCriteria(builder);
        appendTransactionTypeCriteria(builder);
        appendSlowOnlyCriteria(builder);
        appendErrorOnlyCriteria(builder);
        appendTransactionNameCriteria(builder);
        appendHeadlineCriteria(builder);
        appendErrorCriteria(builder);
        appendUserCriteria(builder);
        appendOrderByAndLimit(builder);
        return builder.build();
    }

    private @Nullable ParameterizedSql getCustomAttributeCriteria() {
        String sql = "";
        List<Object> args = Lists.newArrayList();
        String customAttributeName = query.customAttributeName();
        if (!Strings.isNullOrEmpty(customAttributeName)) {
            sql += " upper(attr.name) = ? and";
            args.add(customAttributeName.toUpperCase(Locale.ENGLISH));
        }
        StringComparator customAttributeValueComparator = query.customAttributeValueComparator();
        String customAttributeValue = query.customAttributeValue();
        if (customAttributeValueComparator != null
                && !Strings.isNullOrEmpty(customAttributeValue)) {
            sql += " upper(attr.value) " + customAttributeValueComparator.getComparator()
                    + " ? and";
            args.add(customAttributeValueComparator.formatParameter(customAttributeValue));
        }
        if (sql.equals("")) {
            return null;
        } else {
            return ImmutableParameterizedSql.of(sql, args);
        }
    }

    private void appendDurationCriteria(ParameterizedSqlBuilder builder) {
        long durationNanosLow = query.durationNanosLow();
        if (durationNanosLow != 0) {
            builder.appendText(" and trace.duration_nanos >= ?");
            builder.addArg(durationNanosLow);
        }
        Long durationNanosHigh = query.durationNanosHigh();
        if (durationNanosHigh != null) {
            builder.appendText(" and trace.duration_nanos <= ?");
            builder.addArg(durationNanosHigh);
        }
    }

    private void appendTransactionTypeCriteria(ParameterizedSqlBuilder builder) {
        String transactionType = query.transactionType();
        if (!Strings.isNullOrEmpty(transactionType)) {
            builder.appendText(" and trace.transaction_type = ?");
            builder.addArg(transactionType);
        }
    }

    private void appendSlowOnlyCriteria(ParameterizedSqlBuilder builder) {
        if (query.slowOnly()) {
            builder.appendText(" and trace.slow = ?");
            builder.addArg(true);
        }
    }

    private void appendErrorOnlyCriteria(ParameterizedSqlBuilder builder) {
        if (query.errorOnly()) {
            builder.appendText(" and trace.error = ?");
            builder.addArg(true);
        }
    }

    private void appendTransactionNameCriteria(ParameterizedSqlBuilder builder) {
        StringComparator transactionNameComparator = query.transactionNameComparator();
        String transactionName = query.transactionName();
        if (transactionNameComparator != null && !Strings.isNullOrEmpty(transactionName)) {
            builder.appendText(" and upper(trace.transaction_name) "
                    + transactionNameComparator.getComparator() + " ?");
            builder.addArg(transactionNameComparator.formatParameter(transactionName));
        }
    }

    private void appendHeadlineCriteria(ParameterizedSqlBuilder builder) {
        StringComparator headlineComparator = query.headlineComparator();
        String headline = query.headline();
        if (headlineComparator != null && !Strings.isNullOrEmpty(headline)) {
            builder.appendText(
                    " and upper(trace.headline) " + headlineComparator.getComparator() + " ?");
            builder.addArg(headlineComparator.formatParameter(headline));
        }
    }

    private void appendErrorCriteria(ParameterizedSqlBuilder builder) {
        StringComparator errorComparator = query.errorComparator();
        String error = query.error();
        if (errorComparator != null && !Strings.isNullOrEmpty(error)) {
            builder.appendText(
                    " and upper(trace.error_message) " + errorComparator.getComparator() + " ?");
            builder.addArg(errorComparator.formatParameter(error));
        }
    }

    private void appendUserCriteria(ParameterizedSqlBuilder builder) {
        StringComparator userComparator = query.userComparator();
        String user = query.user();
        if (userComparator != null && !Strings.isNullOrEmpty(user)) {
            builder.appendText(" and upper(trace.user) " + userComparator.getComparator() + " ?");
            builder.addArg(userComparator.formatParameter(user));
        }
    }

    private void appendOrderByAndLimit(ParameterizedSqlBuilder builder) {
        builder.appendText(" order by trace.duration_nanos");
        if (query.limit() != 0) {
            // +1 is to identify if limit was exceeded
            builder.appendText(" desc limit ?");
            builder.addArg(query.limit() + 1);
        }
    }

    @Value.Immutable
    @Styles.AllParameters
    abstract static class ParameterizedSql {

        abstract @Untainted String sql();
        abstract ImmutableList<Object> args();

        Object[] argsAsArray() {
            return args().toArray(new Object[args().size()]);
        }
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
