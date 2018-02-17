/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, @Nullable Version 2.0 (@Nullable the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, @Nullable software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, @Nullable either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.plugin.api.internal;

import org.glowroot.agent.plugin.api.Logger;

public interface LoggerFactory {

    Logger getLogger(Class<?> clazz);
}
