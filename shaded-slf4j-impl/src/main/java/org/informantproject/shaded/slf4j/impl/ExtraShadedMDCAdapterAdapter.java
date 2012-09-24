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
package org.informantproject.shaded.slf4j.impl;

import java.util.Map;

import org.informantproject.shaded.slf4j.spi.MDCAdapter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ExtraShadedMDCAdapterAdapter implements MDCAdapter {

    private final org.informantproject.shaded.slf4jx.spi.MDCAdapter mdcAdapter;

    ExtraShadedMDCAdapterAdapter(org.informantproject.shaded.slf4jx.spi.MDCAdapter mdcAdapter) {
        this.mdcAdapter = mdcAdapter;
    }

    public void put(String key, String val) {
        mdcAdapter.put(key, val);
    }

    public String get(String key) {
        return mdcAdapter.get(key);
    }

    public void remove(String key) {
        mdcAdapter.remove(key);
    }

    public void clear() {
        mdcAdapter.clear();
    }

    public Map<?, ?> getCopyOfContextMap() {
        return mdcAdapter.getCopyOfContextMap();
    }

    public void setContextMap(@SuppressWarnings("rawtypes") Map contextMap) {
        mdcAdapter.setContextMap(contextMap);
    }
}
