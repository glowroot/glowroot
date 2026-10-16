/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.storage.postgres;

import java.sql.Timestamp;

import com.google.common.primitives.Ints;
import org.glowroot.common.util.Clock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Postgres stand-in for Cassandra TTL: store {@code expires_at} and filter on read.
 */
final class PostgresExpires {

    private PostgresExpires() {}

    static Timestamp fromTtlSeconds(int ttlSeconds, long captureTime, Clock clock) {
        if (ttlSeconds == 0) {
            // far future — Postgres timestamptz practical max
            return Timestamp.valueOf("9999-12-31 23:59:59");
        }
        long captureTimeAgoSeconds =
                MILLISECONDS.toSeconds(clock.currentTimeMillis() - captureTime);
        int adjustedTTL = Ints.saturatedCast(ttlSeconds - captureTimeAgoSeconds);
        adjustedTTL = Math.max(adjustedTTL, 60);
        return new Timestamp(clock.currentTimeMillis() + SECONDS.toMillis(adjustedTTL));
    }
}
