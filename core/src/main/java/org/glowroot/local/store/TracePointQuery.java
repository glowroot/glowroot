/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.local.store;

import java.util.List;
import java.util.Locale;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import dataflow.quals.Pure;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TracePointQuery {

    private final long captureTimeFrom;
    private final long captureTimeTo;
    private final long durationLow;
    private final long durationHigh;
    @Nullable
    private final Boolean background;
    private final boolean errorOnly;
    private final boolean fineOnly;
    @Nullable
    private final StringComparator groupingComparator;
    @Nullable
    private final String grouping;
    @Nullable
    private final StringComparator errorComparator;
    @Nullable
    private final String error;
    @Nullable
    private final StringComparator userComparator;
    @Nullable
    private final String user;
    private final int limit;

    public TracePointQuery(long captureTimeFrom, long captureTimeTo, long durationLow,
            long durationHigh, @Nullable Boolean background, boolean errorOnly, boolean fineOnly,
            @Nullable StringComparator groupingComparator, @Nullable String grouping,
            @Nullable StringComparator errorComparator, @Nullable String error,
            @Nullable StringComparator userComparator, @Nullable String user, int limit) {
        this.captureTimeFrom = captureTimeFrom;
        this.captureTimeTo = captureTimeTo;
        this.durationLow = durationLow;
        this.durationHigh = durationHigh;
        this.background = background;
        this.errorOnly = errorOnly;
        this.fineOnly = fineOnly;
        this.groupingComparator = groupingComparator;
        this.grouping = grouping;
        this.errorComparator = errorComparator;
        this.error = error;
        this.userComparator = userComparator;
        this.user = user;
        this.limit = limit;
    }

    ParameterizedSql getParameterizedSql() {
        // all of these columns should be in the same index so h2 can return result set directly
        // from the index without having to reference the table for each row
        String sql = "select id, capture_time, duration, error from snapshot where"
                + " capture_time >= ? and capture_time <= ?";
        List<Object> args = Lists.newArrayList();
        args.add(captureTimeFrom);
        args.add(captureTimeTo);
        if (durationLow != 0) {
            sql += " and duration >= ?";
            args.add(durationLow);
        }
        if (durationHigh != Long.MAX_VALUE) {
            sql += " and duration <= ?";
            args.add(durationHigh);
        }
        if (background != null) {
            sql += " and background = ?";
            args.add(background);
        }
        if (errorOnly) {
            sql += " and error = ?";
            args.add(true);
        }
        if (fineOnly) {
            sql += " and fine = ?";
            args.add(true);
        }
        if (groupingComparator != null && grouping != null) {
            sql += " and upper(grouping) " + groupingComparator.getComparator() + " ?";
            args.add(groupingComparator.formatParameter(grouping.toUpperCase(Locale.ENGLISH)));
        }
        if (errorComparator != null && error != null) {
            sql += " and upper(error_message) " + errorComparator.getComparator() + " ?";
            args.add(errorComparator.formatParameter(error.toUpperCase(Locale.ENGLISH)));
        }
        if (userComparator != null && user != null) {
            sql += " and upper(user) " + userComparator.getComparator() + " ?";
            args.add(userComparator.formatParameter(user.toUpperCase(Locale.ENGLISH)));
        }
        sql += " order by duration desc limit ?";
        args.add(limit);
        return new ParameterizedSql(sql, args);
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("captureTimeFrom", captureTimeFrom)
                .add("captureTimeTo", captureTimeTo)
                .add("durationLow", durationLow)
                .add("durationHigh", durationHigh)
                .add("background", background)
                .add("errorOnly", errorOnly)
                .add("fineOnly", fineOnly)
                .add("groupingComparator", groupingComparator)
                .add("grouping", grouping)
                .add("errorComparator", errorComparator)
                .add("error", error)
                .add("userComparator", userComparator)
                .add("user", user)
                .add("limit", limit)
                .toString();
    }

    public static enum StringComparator {

        BEGINS("like", "%s%%"),
        EQUALS("=", "%s"),
        ENDS("like", "%%%s"),
        CONTAINS("like", "%%%s%%"),
        NOT_CONTAINS("not like", "%%%s%%");

        private final String comparator;
        private final String parameterFormat;

        private StringComparator(String comparator, String parameterTemplate) {
            this.comparator = comparator;
            this.parameterFormat = parameterTemplate;
        }

        String formatParameter(String parameter) {
            return String.format(parameterFormat, parameter);
        }

        String getComparator() {
            return comparator;
        }
    }

    static class ParameterizedSql {

        private final String sql;
        private final List<Object> args;

        private ParameterizedSql(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }

        String getSql() {
            return sql;
        }

        List<Object> getArgs() {
            return args;
        }
    }
}
