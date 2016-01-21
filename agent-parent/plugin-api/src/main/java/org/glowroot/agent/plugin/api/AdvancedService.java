/*
 * Copyright 2016 the original author or authors.
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

public interface AdvancedService {

    /**
     * Returns whether a transaction is already being captured.
     * 
     * This method has very limited use. It should only be used by top-level pointcuts that define a
     * transaction, and that do not want to create a entry if they are already inside of an existing
     * transaction.
     */
    boolean isInTransaction();
}
