/*
 * Copyright 2017-2018 the original author or authors.
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
package org.glowroot.central.repo;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;

import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.repo.ConfigRepository.RollupConfig;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.util.Clock;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class Common {

    private Common() {}

    static int getAdjustedTTL(int ttl, long captureTime, Clock clock) {
        if (ttl == 0) {
            return 0;
        }
        long captureTimeAgoSeconds =
                MILLISECONDS.toSeconds(clock.currentTimeMillis() - captureTime);
        // need saturated cast because captureTimeAgoSeconds may be negative
        int adjustedTTL = Ints.saturatedCast(ttl - captureTimeAgoSeconds);
        // max is a safety guard
        return Math.max(adjustedTTL, 60);
    }

    static int getNeedsRollupAdjustedTTL(int adjustedTTL, List<RollupConfig> rollupConfigs) {
        if (adjustedTTL == 0) {
            return 0;
        }
        long maxRollupInterval = rollupConfigs.get(rollupConfigs.size() - 1).intervalMillis();
        // reduced by an extra 1 hour to make sure that once needs rollup record is retrieved,
        // there is plenty of time to read the all of the data records in the interval before they
        // expire (reading partially expired interval can lead to non-idempotent rollups)
        int needsRollupAdjustedTTL =
                adjustedTTL - Ints.saturatedCast(MILLISECONDS.toSeconds(maxRollupInterval)) - 3600;
        // max is a safety guard
        return Math.max(needsRollupAdjustedTTL, 60);
    }

    static List<NeedsRollup> getNeedsRollupList(String agentRollupId, int rollupLevel,
            long rollupIntervalMillis, List<PreparedStatement> readNeedsRollup, Session session,
            Clock clock) throws Exception {
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.execute(boundStatement);
        Map<Long, NeedsRollup> needsRollupMap = Maps.newLinkedHashMap();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            UUID uniqueness = row.getUUID(i++);
            Set<String> keys = checkNotNull(row.getSet(i++, String.class));
            NeedsRollup needsRollup = needsRollupMap.get(captureTime);
            if (needsRollup == null) {
                needsRollup = new NeedsRollup(captureTime);
                needsRollupMap.put(captureTime, needsRollup);
            }
            needsRollup.keys.addAll(keys);
            needsRollup.uniquenessKeysForDeletion.add(uniqueness);
        }
        if (needsRollupMap.isEmpty()) {
            return ImmutableList.of();
        }
        List<NeedsRollup> needsRollupList = Lists.newArrayList(needsRollupMap.values());
        NeedsRollup lastNeedsRollup = needsRollupList.get(needsRollupList.size() - 1);
        if (lastNeedsRollup.getCaptureTime() > clock.currentTimeMillis() - rollupIntervalMillis) {
            // normally, the last "needs rollup" capture time is in the near future, so don't roll
            // it up since it is likely still being added to
            //
            // this is mostly to avoid rolling up this data twice, but also currently the UI assumes
            // when it finds rolled up data, it doesn't check for non-rolled up data for same
            // interval
            //
            // the above conditional is to force the rollup of the last "needs rollup" if it is more
            // than one rollup interval in the past, otherwise the last "needs rollup" could expire
            // due to TTL prior to it being rolled up
            needsRollupList.remove(needsRollupList.size() - 1);
        }
        return needsRollupList;
    }

    static List<NeedsRollupFromChildren> getNeedsRollupFromChildrenList(String agentRollupId,
            PreparedStatement readNeedsRollupFromChild, Session session) throws Exception {
        BoundStatement boundStatement = readNeedsRollupFromChild.bind();
        boundStatement.setString(0, agentRollupId);
        ResultSet results = session.execute(boundStatement);
        Map<Long, NeedsRollupFromChildren> needsRollupFromChildrenMap = Maps.newLinkedHashMap();
        for (Row row : results) {
            int i = 0;
            long captureTime = checkNotNull(row.getTimestamp(i++)).getTime();
            UUID uniqueness = row.getUUID(i++);
            String childAgentRollupId = checkNotNull(row.getString(i++));
            Set<String> keys = checkNotNull(row.getSet(i++, String.class));
            NeedsRollupFromChildren needsRollup = needsRollupFromChildrenMap.get(captureTime);
            if (needsRollup == null) {
                needsRollup = new NeedsRollupFromChildren(captureTime);
                needsRollupFromChildrenMap.put(captureTime, needsRollup);
            }
            for (String key : keys) {
                needsRollup.keys.put(key, childAgentRollupId);
            }
            needsRollup.uniquenessKeysForDeletion.add(uniqueness);
        }
        return ImmutableList.copyOf(needsRollupFromChildrenMap.values());
    }

    // it is important that the insert into next needs_rollup happens after present
    // rollup and before deleting present rollup
    // if insert before present rollup then possible for the next rollup to occur before
    // present rollup has completed
    // if insert after deleting present rollup then possible for error to occur in between
    // and insert would never happen
    static void postRollup(String agentRollupId, long captureTime, Set<String> keys,
            Set<UUID> uniquenessKeysForDeletion, @Nullable Long nextRollupIntervalMillis,
            @Nullable PreparedStatement insertNeedsRollup, PreparedStatement deleteNeedsRollup,
            int needsRollupAdjustedTTL, Session session) throws Exception {
        if (nextRollupIntervalMillis != null) {
            checkNotNull(insertNeedsRollup);
            long rollupCaptureTime = Utils.getRollupCaptureTime(captureTime,
                    nextRollupIntervalMillis);
            BoundStatement boundStatement = insertNeedsRollup.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setTimestamp(i++, new Date(rollupCaptureTime));
            boundStatement.setUUID(i++, UUIDs.timeBased());
            boundStatement.setSet(i++, keys);
            boundStatement.setInt(i++, needsRollupAdjustedTTL);
            // intentionally not async, see method-level comment
            session.execute(boundStatement);
        }
        List<Future<?>> futures = Lists.newArrayList();
        for (UUID uniqueness : uniquenessKeysForDeletion) {
            BoundStatement boundStatement = deleteNeedsRollup.bind();
            int i = 0;
            boundStatement.setString(i++, agentRollupId);
            boundStatement.setTimestamp(i++, new Date(captureTime));
            boundStatement.setUUID(i++, uniqueness);
            futures.add(session.executeAsync(boundStatement));
        }
        MoreFutures.waitForAll(futures);
    }

    static class NeedsRollup {

        private final long captureTime;
        private final Set<String> keys = Sets.newHashSet(); // transaction types or gauge names
        private final Set<UUID> uniquenessKeysForDeletion = Sets.newHashSet();

        private NeedsRollup(long captureTime) {
            this.captureTime = captureTime;
        }

        long getCaptureTime() {
            return captureTime;
        }

        Set<String> getKeys() {
            return keys;
        }

        Set<UUID> getUniquenessKeysForDeletion() {
            return uniquenessKeysForDeletion;
        }
    }

    static class NeedsRollupFromChildren {

        private final long captureTime;
        // map keys are transaction types or gauge names
        // map values are childAgentRollupIds
        private final Multimap<String, String> keys = HashMultimap.create();
        private final Set<UUID> uniquenessKeysForDeletion = Sets.newHashSet();

        private NeedsRollupFromChildren(long captureTime) {
            this.captureTime = captureTime;
        }

        long getCaptureTime() {
            return captureTime;
        }

        Multimap<String, String> getKeys() {
            return keys;
        }

        Set<UUID> getUniquenessKeysForDeletion() {
            return uniquenessKeysForDeletion;
        }
    }
}
