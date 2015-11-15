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
package org.glowroot.storage.simplerepo;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveTraceRepository.TraceKind;
import org.glowroot.common.live.LiveTraceRepository.TracePointCriteria;
import org.glowroot.common.live.StringComparator;
import org.glowroot.common.util.Styles;

class TracePointQueryBuilder {

    private final TraceKind traceKind;
    private final String serverRollup;
    private final String transactionType;
    private final @Nullable String transactionName;
    private final long captureTimeFrom;
    private final long captureTimeTo;
    private final TracePointCriteria criteria;
    private final int limit;

    TracePointQueryBuilder(TraceKind traceKind, String serverRollup, String transactionType,
            @Nullable String transactionName, long captureTimeFrom, long captureTimeTo,
            TracePointCriteria criteria, int limit) {
        this.traceKind = traceKind;
        this.serverRollup = serverRollup;
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.captureTimeFrom = captureTimeFrom;
        this.captureTimeTo = captureTimeTo;
        this.criteria = criteria;
        this.limit = limit;
    }

    // capture time lower bound is non-inclusive so that aggregate data intervals can be mapped
    // to their trace points (aggregate data intervals are non-inclusive on lower bound and
    // inclusive on upper bound)
    ParameterizedSql getParameterizedSql() {
        ParameterizedSqlBuilder builder = new ParameterizedSqlBuilder();
        builder.appendText("select trace.server_id, trace.id, trace.capture_time,"
                + " trace.duration_nanos, trace.error from trace");
        ParameterizedSql criteria = getAttributeCriteria();
        if (criteria == null) {
            builder.appendText(" where");
        } else {
            builder.appendText(", trace_attribute attr where attr.server_id = trace.server_id"
                    + " and attr.id = trace.id and attr.capture_time > ? and attr.capture_time <= ?"
                    + " and" + criteria.sql());
            builder.addArg(captureTimeFrom);
            builder.addArg(captureTimeTo);
            builder.addArgs(criteria.args());

        }
        // FIXME maintain table of server_rollup/server_rollup associations and join that here
        // (maintain this table on agent "Hello", wipe out prior associations and add new ones)
        builder.appendText(
                " trace.server_id = ? and trace.capture_time > ? and trace.capture_time <= ?");
        builder.addArg(serverRollup);
        builder.addArg(captureTimeFrom);
        builder.addArg(captureTimeTo);
        appendTotalNanosCriteria(builder);
        appendTransactionTypeCriteria(builder);
        appendTransactionNameCriteria(builder);
        appendTraceKindCriteria(builder);
        appendHeadlineCriteria(builder);
        appendErrorCriteria(builder);
        appendUserCriteria(builder);
        appendOrderByAndLimit(builder);
        return builder.build();
    }

    private @Nullable ParameterizedSql getAttributeCriteria() {
        String sql = "";
        List<Object> args = Lists.newArrayList();
        String attributeName = criteria.attributeName();
        if (!Strings.isNullOrEmpty(attributeName)) {
            sql += " upper(attr.name) = ? and";
            args.add(attributeName.toUpperCase(Locale.ENGLISH));
        }
        StringComparator attributeValueComparator = criteria.attributeValueComparator();
        String attributeValue = criteria.attributeValue();
        if (attributeValueComparator != null && !Strings.isNullOrEmpty(attributeValue)) {
            sql += " upper(attr.value) " + attributeValueComparator.getComparator() + " ? and";
            args.add(attributeValueComparator.formatParameter(attributeValue));
        }
        if (sql.equals("")) {
            return null;
        } else {
            return ImmutableParameterizedSql.of(sql, args);
        }
    }

    private void appendTotalNanosCriteria(ParameterizedSqlBuilder builder) {
        long totalNanosLow = criteria.durationNanosLow();
        if (totalNanosLow != 0) {
            builder.appendText(" and trace.duration_nanos >= ?");
            builder.addArg(totalNanosLow);
        }
        Long totalNanosHigh = criteria.durationNanosHigh();
        if (totalNanosHigh != null) {
            builder.appendText(" and trace.duration_nanos <= ?");
            builder.addArg(totalNanosHigh);
        }
    }

    private void appendTransactionTypeCriteria(ParameterizedSqlBuilder builder) {
        builder.appendText(" and trace.transaction_type = ?");
        builder.addArg(transactionType);
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

    private void appendTransactionNameCriteria(ParameterizedSqlBuilder builder) {
        if (transactionName == null) {
            StringComparator transactionNameComparator = criteria.transactionNameComparator();
            String transactionName = criteria.transactionName();
            if (transactionNameComparator != null && !Strings.isNullOrEmpty(transactionName)) {
                builder.appendText(" and upper(trace.transaction_name) "
                        + transactionNameComparator.getComparator() + " ?");
                builder.addArg(transactionNameComparator.formatParameter(transactionName));
            }
        } else {
            builder.appendText(" and trace.transaction_name = ?");
            builder.addArg(transactionName);
        }
    }

    private void appendHeadlineCriteria(ParameterizedSqlBuilder builder) {
        StringComparator headlineComparator = criteria.headlineComparator();
        String headline = criteria.headline();
        if (headlineComparator != null && !Strings.isNullOrEmpty(headline)) {
            builder.appendText(
                    " and upper(trace.headline) " + headlineComparator.getComparator() + " ?");
            builder.addArg(headlineComparator.formatParameter(headline));
        }
    }

    private void appendErrorCriteria(ParameterizedSqlBuilder builder) {
        StringComparator errorComparator = criteria.errorMessageComparator();
        String error = criteria.errorMessage();
        if (errorComparator != null && !Strings.isNullOrEmpty(error)) {
            builder.appendText(
                    " and upper(trace.error_message) " + errorComparator.getComparator() + " ?");
            builder.addArg(errorComparator.formatParameter(error));
        }
    }

    private void appendUserCriteria(ParameterizedSqlBuilder builder) {
        StringComparator userComparator = criteria.userComparator();
        String user = criteria.user();
        if (userComparator != null && !Strings.isNullOrEmpty(user)) {
            builder.appendText(
                    " and upper(trace.user_id) " + userComparator.getComparator() + " ?");
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
