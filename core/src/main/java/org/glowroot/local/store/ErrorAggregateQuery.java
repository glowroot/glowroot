/*
 * Copyright 2014 the original author or authors.
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

import com.google.common.base.Objects;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ErrorAggregateQuery {

    private final long captureTimeFrom;
    private final long captureTimeTo;
    private final List<String> includes;
    private final List<String> excludes;
    private final int limit;

    public ErrorAggregateQuery(long captureTimeFrom, long captureTimeTo, List<String> includes,
            List<String> excludes, int limit) {
        this.captureTimeFrom = captureTimeFrom;
        this.captureTimeTo = captureTimeTo;
        this.includes = includes;
        this.excludes = excludes;
        this.limit = limit;
    }

    ParameterizedSql getParameterizedSql() {
        String sql = "select transaction_name, error_message, count(*) from snapshot where"
                + " error = ? and capture_time >= ? and capture_time <= ?";
        List<Object> args = Lists.newArrayList();
        args.add(true);
        args.add(captureTimeFrom);
        args.add(captureTimeTo);
        for (String include : includes) {
            sql += " and upper(error_message) like ?";
            args.add('%' + include.toUpperCase(Locale.ENGLISH) + '%');
        }
        for (String exclude : excludes) {
            sql += " and upper(error_message) not like ?";
            args.add('%' + exclude.toUpperCase(Locale.ENGLISH) + '%');
        }
        sql += " group by transaction_name, error_message order by count(*) desc limit ?";
        args.add(limit);
        return new ParameterizedSql(sql, args);
    }

    /*@Pure*/
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("captureTimeFrom", captureTimeFrom)
                .add("captureTimeTo", captureTimeTo)
                .add("includes", includes)
                .add("excludes", excludes)
                .add("limit", limit)
                .toString();
    }
}
