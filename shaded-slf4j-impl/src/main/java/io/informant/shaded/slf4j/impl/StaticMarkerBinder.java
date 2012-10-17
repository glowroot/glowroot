/**
 * Copyright 2012 the original author or authors.
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
package io.informant.shaded.slf4j.impl;

import io.informant.shaded.slf4j.IMarkerFactory;
import io.informant.shaded.slf4j.helpers.BasicMarkerFactory;
import io.informant.shaded.slf4j.spi.MarkerFactoryBinder;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StaticMarkerBinder implements MarkerFactoryBinder {

    public static final StaticMarkerBinder SINGLETON = new StaticMarkerBinder();

    private final IMarkerFactory markerFactory = new BasicMarkerFactory();

    private StaticMarkerBinder() {}

    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    public String getMarkerFactoryClassStr() {
        return BasicMarkerFactory.class.getName();
    }
}
