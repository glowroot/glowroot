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
package org.glowroot.transaction.model;

import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;
import org.immutables.value.Value;

import org.glowroot.api.TimerName;

@Value.Immutable
public abstract class TimerNameImplBase implements TimerName {

    private static final AtomicInteger nextSpecialHashCode = new AtomicInteger();

    @VisibleForTesting
    @Value.Parameter
    public abstract String name();

    @Value.Derived
    int specialHashCode() {
        return nextSpecialHashCode.getAndIncrement();
    }
}
