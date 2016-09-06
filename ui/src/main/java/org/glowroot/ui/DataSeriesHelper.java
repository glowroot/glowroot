/*
 * Copyright 2014-2016 the original author or authors.
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

import org.glowroot.common.repo.Utils;

class DataSeriesHelper {

    private final long liveCaptureTime;
    private final long dataPointIntervalMillis;

    DataSeriesHelper(long liveCaptureTime, long dataPointIntervalMillis) {
        this.liveCaptureTime = liveCaptureTime;
        this.dataPointIntervalMillis = dataPointIntervalMillis;
    }

    void addInitialUpslopeIfNeeded(long requestFrom, long captureTime,
            List<DataSeries> dataSeriesList, @Nullable DataSeries otherDataSeries) {
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

    void addFinalDownslopeIfNeeded(List<DataSeries> dataSeriesList,
            @Nullable DataSeries otherDataSeries, long lastCaptureTime) {
        long downslopeCaptureTime = finalDownslopeCaptureTime(lastCaptureTime);
        if (downslopeCaptureTime != 0) {
            // bring down to zero
            for (DataSeries dataSeries : dataSeriesList) {
                dataSeries.add(downslopeCaptureTime, 0);
            }
            if (otherDataSeries != null) {
                otherDataSeries.add(downslopeCaptureTime, 0);
            }
        }
    }

    void addInitialUpslopeIfNeeded(long requestFrom, long captureTime, DataSeries dataSeries) {
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

    void addFinalDownslopeIfNeeded(DataSeries dataSeries, long lastCaptureTime) {
        long downslopeCaptureTime = finalDownslopeCaptureTime(lastCaptureTime);
        if (downslopeCaptureTime != 0) {
            // bring down to zero
            dataSeries.add(downslopeCaptureTime, 0);
        }
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

    // returns 0 if final downslope is not needed
    private long finalDownslopeCaptureTime(long lastCaptureTime) {
        if (liveCaptureTime - lastCaptureTime > 1.5 * dataPointIntervalMillis) {
            return lastCaptureTime + dataPointIntervalMillis;
        }
        return 0;
    }

    private long getPriorCaptureTime(long captureTime) {
        return getCurrentCaptureTime(captureTime) - dataPointIntervalMillis;
    }

    // captureTime may be for active point which doesn't line up on data point interval
    private long getCurrentCaptureTime(long captureTime) {
        return Utils.getRollupCaptureTime(captureTime, dataPointIntervalMillis);
    }
}
