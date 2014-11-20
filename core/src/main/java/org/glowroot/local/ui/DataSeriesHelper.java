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
package org.glowroot.local.ui;

import java.util.List;

import javax.annotation.Nullable;

import org.glowroot.common.Clock;

class DataSeriesHelper {

    private final Clock clock;
    private final long fixedAggregateIntervalMillis;

    DataSeriesHelper(Clock clock, long fixedAggregateIntervalMillis) {
        this.clock = clock;
        this.fixedAggregateIntervalMillis = fixedAggregateIntervalMillis;
    }

    void addInitialUpslope(long requestFrom, long captureTime, List<DataSeries> dataSeriesList,
            @Nullable DataSeries otherDataSeries) {
        long millisecondsFromEdge = captureTime - requestFrom;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
        }
        if (otherDataSeries != null) {
            otherDataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
        }
    }

    void addGapIfNeeded(long lastCaptureTime, long captureTime, List<DataSeries> dataSeriesList,
            @Nullable DataSeries otherDataSeries) {
        long millisecondsSinceLastPoint = captureTime - lastCaptureTime;
        if (millisecondsSinceLastPoint < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        for (DataSeries dataSeries : dataSeriesList) {
            addGap(dataSeries, lastCaptureTime, captureTime);
        }
        if (otherDataSeries != null) {
            addGap(otherDataSeries, lastCaptureTime, captureTime);
        }
    }

    void addFinalDownslope(long requestCaptureTimeTo, List<DataSeries> dataSeriesList,
            @Nullable DataSeries otherDataSeries, long lastCaptureTime) {
        long millisecondsAgoFromNow = clock.currentTimeMillis() - lastCaptureTime;
        if (millisecondsAgoFromNow < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = requestCaptureTimeTo - lastCaptureTime;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
        }
        if (otherDataSeries != null) {
            otherDataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
        }
    }

    void addInitialUpslope(long requestFrom, long captureTime, DataSeries dataSeries) {
        long millisecondsFromEdge = captureTime - requestFrom;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring up from zero
        dataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
    }

    void addGapIfNeeded(long lastCaptureTime, long captureTime, DataSeries dataSeries) {
        long millisecondsSinceLastPoint = captureTime - lastCaptureTime;
        if (millisecondsSinceLastPoint < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        addGap(dataSeries, lastCaptureTime, captureTime);
    }

    void addFinalDownslope(long requestCaptureTimeTo, DataSeries dataSeries,
            long lastCaptureTime) {
        long millisecondsAgoFromNow = clock.currentTimeMillis() - lastCaptureTime;
        if (millisecondsAgoFromNow < fixedAggregateIntervalMillis * 1.5) {
            return;
        }
        long millisecondsFromEdge = requestCaptureTimeTo - lastCaptureTime;
        if (millisecondsFromEdge < fixedAggregateIntervalMillis / 2) {
            return;
        }
        // bring down to zero
        dataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
    }

    private void addGap(DataSeries dataSeries, long lastCaptureTime, long captureTime) {
        dataSeries.add(lastCaptureTime + fixedAggregateIntervalMillis / 2, 0);
        dataSeries.addNull();
        dataSeries.add(captureTime - fixedAggregateIntervalMillis / 2, 0);
    }
}
