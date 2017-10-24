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
package org.glowroot.common.model;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;

import org.glowroot.common.model.SyntheticResult.ErrorInterval;

import static com.google.common.base.Preconditions.checkNotNull;

// error intervals and gaps must be added in order
public class ErrorIntervalCollector {

    private final List<ErrorInterval> mergedErrorIntervals = Lists.newArrayList();

    private long currErrorFrom;
    private long currErrorTo;
    private int currErrorCount;
    private @Nullable String currErrorMessage;
    private boolean currErrorDoNotMergeToTheLeft;

    public void addErrorIntervals(List<ErrorInterval> errorIntervals) {
        for (ErrorInterval errorInterval : errorIntervals) {
            addErrorInterval(errorInterval);
        }
    }

    private void addErrorInterval(ErrorInterval errorInterval) {
        if (shouldMergeIntoCurrentInterval(errorInterval)) {
            // merge into current interval
            currErrorTo = errorInterval.to();
            currErrorCount += errorInterval.count();
        } else {
            // create new interval
            if (currErrorMessage != null) {
                // close current interval
                mergedErrorIntervals.add(getCurrErrorInterval(errorInterval.doNotMergeToTheLeft()));
            }
            currErrorTo = errorInterval.to();
            currErrorFrom = errorInterval.from();
            currErrorCount = errorInterval.count();
            currErrorMessage = errorInterval.message();
            currErrorDoNotMergeToTheLeft = errorInterval.doNotMergeToTheLeft();
        }
        if (errorInterval.doNotMergeToTheRight()) {
            // close current interval
            mergedErrorIntervals.add(getCurrErrorInterval(true));
            currErrorMessage = null;
        }
    }

    public void addGap() {
        if (currErrorMessage != null) {
            mergedErrorIntervals.add(getCurrErrorInterval(true));
            currErrorMessage = null;
        }
    }

    public List<ErrorInterval> getMergedErrorIntervals() {
        List<ErrorInterval> mergedErrorIntervals = Lists.newArrayList(this.mergedErrorIntervals);
        if (currErrorMessage != null) {
            mergedErrorIntervals.add(getCurrErrorInterval(false));
        }
        return mergedErrorIntervals;
    }

    private boolean shouldMergeIntoCurrentInterval(ErrorInterval errorInterval) {
        if (currErrorMessage == null || errorInterval.doNotMergeToTheLeft()) {
            return false;
        }
        return errorInterval.message().equals(currErrorMessage);
    }

    private ImmutableErrorInterval getCurrErrorInterval(boolean doNotMergeToTheRight) {
        return ImmutableErrorInterval.builder()
                .from(currErrorFrom)
                .to(currErrorTo)
                .count(currErrorCount)
                .message(checkNotNull(currErrorMessage))
                .doNotMergeToTheLeft(currErrorDoNotMergeToTheLeft)
                .doNotMergeToTheRight(doNotMergeToTheRight)
                .build();
    }
}
