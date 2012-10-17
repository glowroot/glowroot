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

import io.informant.shaded.slf4j.spi.MDCAdapter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StaticMDCBinder {

    public static final StaticMDCBinder SINGLETON = new StaticMDCBinder();

    private StaticMDCBinder() {}

    public MDCAdapter getMDCA() {
        if (Configuration.useUnshadedSlf4j()) {
            return new UnshadedMDCAdapterAdapter(
                    org.slf4j.impl.StaticMDCBinder.SINGLETON.getMDCA());
        } else {
            return new ExtraShadedMDCAdapterAdapter(
                    io.informant.shaded.slf4jx.impl.StaticMDCBinder.SINGLETON.getMDCA());
        }
    }

    public String getMDCAdapterClassStr() {
        if (Configuration.useUnshadedSlf4j()) {
            return io.informant.shaded.slf4j.impl.StaticMDCBinder.SINGLETON
                    .getMDCAdapterClassStr();
        } else {
            return io.informant.shaded.slf4jx.impl.StaticMDCBinder.SINGLETON
                    .getMDCAdapterClassStr();
        }
    }
}
