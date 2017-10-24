/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import org.immutables.value.Value;

import org.glowroot.common.model.SyntheticResult.ErrorInterval;

import static com.google.common.base.Preconditions.checkNotNull;

// error intervals and gaps must be added in order
class MultiErrorIntervalCollector {

    private final List<MultiErrorInterval> mergedErrorIntervals = Lists.newArrayList();

    private long currErrorFrom;
    private long currErrorTo;
    private @Nullable List<ErrorInterval> currErrorIntervals;

    public void addErrorIntervals(List<ErrorInterval> errorIntervals) {
        for (ErrorInterval errorInterval : errorIntervals) {
            addErrorInterval(errorInterval);
        }
    }

    private void addErrorInterval(ErrorInterval errorInterval) {
        if (shouldMergeIntoCurrentInterval(errorInterval)) {
            // merge into current interval
            currErrorTo = errorInterval.to();
            checkNotNull(currErrorIntervals).add(errorInterval);
        } else {
            // create new interval
            if (currErrorIntervals != null) {
                // close current interval
                mergedErrorIntervals.add(getCurrMultiErrorInterval());
            }
            currErrorTo = errorInterval.to();
            currErrorFrom = errorInterval.from();
            currErrorIntervals = Lists.newArrayList();
            currErrorIntervals.add(errorInterval);
        }
        if (errorInterval.doNotMergeToTheRight()) {
            // close current interval
            mergedErrorIntervals.add(getCurrMultiErrorInterval());
            currErrorIntervals = null;
        }
    }

    public List<MultiErrorInterval> getMergedMultiErrorIntervals() {
        List<MultiErrorInterval> mergedErrorIntervals =
                Lists.newArrayList(this.mergedErrorIntervals);
        if (currErrorIntervals != null) {
            mergedErrorIntervals.add(getCurrMultiErrorInterval());
        }
        return mergedErrorIntervals;
    }

    private boolean shouldMergeIntoCurrentInterval(ErrorInterval errorInterval) {
        return currErrorIntervals != null && !errorInterval.doNotMergeToTheLeft();
    }

    private ImmutableMultiErrorInterval getCurrMultiErrorInterval() {
        return ImmutableMultiErrorInterval.builder()
                .from(currErrorFrom)
                .to(currErrorTo)
                .addAllErrorIntervals(checkNotNull(currErrorIntervals))
                .build();
    }

    @Value.Immutable
    public interface MultiErrorInterval {

        long from();
        long to();
        List<ErrorInterval> errorIntervals();
    }
}
