/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.api;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * The detail map can contain {@link String}, {@link Double}, {@link Boolean} and null value types.
 * It can also contain nested maps (which have the same restrictions on value types, including
 * additional levels of nested maps). The detail map cannot have null keys.
 * 
 * As an extra bonus, detail map can also contain
 * org.informantproject.shaded.google.common.base.Optional values which is useful for Maps that do
 * not accept null values, e.g. org.informantproject.shaded.google.common.collect.ImmutableMap.
 * 
 * The detail map does not need to be thread safe as long as it is only instantiated in response to
 * either Supplier<Message>.get() or Message.getDetail() which are called by the thread that needs
 * the map.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface Message {

    String getText();

    @Nullable
    Map<String, ?> getDetail();
}
