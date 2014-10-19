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
package org.glowroot.collector;

import org.glowroot.markers.Immutable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class GaugePoint {

    private final String gaugeName;
    private final long captureTime;
    private final double value;

    public GaugePoint(String gaugeName, long captureTime, double value) {
        this.gaugeName = gaugeName;
        this.captureTime = captureTime;
        this.value = value;
    }

    public String getGaugeName() {
        return gaugeName;
    }

    public long getCaptureTime() {
        return captureTime;
    }

    public double getValue() {
        return value;
    }
}
