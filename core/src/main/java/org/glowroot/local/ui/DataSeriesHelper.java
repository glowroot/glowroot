/*
 * Copyright 2014-2015 the original author or authors.
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
    private final long dataPointIntervalMillis;

    DataSeriesHelper(Clock clock, long dataPointIntervalMillis) {
        this.clock = clock;
        this.dataPointIntervalMillis = dataPointIntervalMillis;
    }

    void addInitialUpslope(long requestFrom, long captureTime, List<DataSeries> dataSeriesList,
            @Nullable DataSeries otherDataSeries) {
        if (captureTime == requestFrom) {
            return;
        }
        // bring up from zero
        long priorCaptureTime = getPriorCaptureTime(captureTime);
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(priorCaptureTime, 0);
        }
        if (otherDataSeries != null) {
            otherDataSeries.add(priorCaptureTime, 0);
        }
    }

    void addGapIfNeeded(long lastCaptureTime, long captureTime, List<DataSeries> dataSeriesList,
            @Nullable DataSeries otherDataSeries) {
        long millisecondsSinceLastPoint = captureTime - lastCaptureTime;
        if (millisecondsSinceLastPoint <= dataPointIntervalMillis) {
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
        if (millisecondsAgoFromNow < dataPointIntervalMillis * 1.5) {
            return;
        }
        if (lastCaptureTime == requestCaptureTimeTo) {
            return;
        }
        // bring down to zero
        //
        // this cannot be an active point that doesn't line up on data point interval since
        // it active point would have met condition above (millisecondsAgoFromNow)
        for (DataSeries dataSeries : dataSeriesList) {
            dataSeries.add(lastCaptureTime + dataPointIntervalMillis, 0);
        }
        if (otherDataSeries != null) {
            otherDataSeries.add(lastCaptureTime + dataPointIntervalMillis, 0);
        }
    }

    void addInitialUpslope(long requestFrom, long captureTime, DataSeries dataSeries) {
        if (captureTime == requestFrom) {
            return;
        }
        // bring up from zero
        long priorCaptureTime = getPriorCaptureTime(captureTime);
        dataSeries.add(priorCaptureTime, 0);
    }

    void addGapIfNeeded(long lastCaptureTime, long captureTime, DataSeries dataSeries) {
        long millisecondsSinceLastPoint = captureTime - lastCaptureTime;
        if (millisecondsSinceLastPoint <= dataPointIntervalMillis) {
            return;
        }
        // gap between points, bring down to zero and then back up from zero to show gap
        addGap(dataSeries, lastCaptureTime, captureTime);
    }

    void addFinalDownslope(long requestCaptureTimeTo, DataSeries dataSeries, long lastCaptureTime) {
        long millisecondsAgoFromNow = clock.currentTimeMillis() - lastCaptureTime;
        if (millisecondsAgoFromNow < dataPointIntervalMillis * 1.5) {
            return;
        }
        if (lastCaptureTime == requestCaptureTimeTo) {
            return;
        }
        // bring down to zero
        //
        // this cannot be an active point that doesn't line up on data point interval since
        // it active point would have met condition above (millisecondsAgoFromNow)
        dataSeries.add(lastCaptureTime + dataPointIntervalMillis, 0);
    }

    private void addGap(DataSeries dataSeries, long lastCaptureTime, long captureTime) {
        long currentCaptureTime = getCurrentCaptureTime(captureTime);
        if (currentCaptureTime - lastCaptureTime == 2 * dataPointIntervalMillis) {
            // only a single missing point
            dataSeries.add(lastCaptureTime + dataPointIntervalMillis, 0);
        } else {
            dataSeries.add(lastCaptureTime + dataPointIntervalMillis, 0);
            dataSeries.addNull();
            dataSeries.add(currentCaptureTime - dataPointIntervalMillis, 0);
        }
    }

    private long getPriorCaptureTime(long captureTime) {
        return getCurrentCaptureTime(captureTime) - dataPointIntervalMillis;
    }

    // captureTime may be for active point which doesn't line up on data point interval
    private long getCurrentCaptureTime(long captureTime) {
        return (long) Math.ceil(captureTime / (double) dataPointIntervalMillis)
                * dataPointIntervalMillis;
    }
}
