/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.model;

import org.immutables.value.Value;

import org.glowroot.common.util.NotAvailableAware;
import org.glowroot.common.util.Styles;

@Styles.Private
@Value.Immutable
public abstract class ThreadInfoData {

    @Value.Default
    public long threadCpuNanos() {
        return NotAvailableAware.NA;
    }

    @Value.Default
    public long threadBlockedNanos() {
        return NotAvailableAware.NA;
    }

    @Value.Default
    public long threadWaitedNanos() {
        return NotAvailableAware.NA;
    }

    @Value.Default
    public long threadAllocatedBytes() {
        return NotAvailableAware.NA;
    }
}
