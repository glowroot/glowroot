/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.plugin.api;

/**
 * A {@link TraceEntry} that also captures query data for aggregation.
 */
public interface QueryEntry extends TraceEntry {

    void rowNavigationAttempted();

    /**
     * Call after successfully getting next row.
     */
    void incrementCurrRow();

    /**
     * Row numbers start at 1 (not 0).
     * 
     * @param row
     */
    void setCurrRow(long row);
}
