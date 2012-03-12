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
package org.informantproject.core.util;

import java.lang.reflect.Type;

import org.informantproject.api.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class OptionalJsonSerializer implements JsonSerializer<Optional<?>> {

    public JsonElement serialize(Optional<?> src, Type typeOfSrc,
            JsonSerializationContext context) {
        return src.isPresent() ? context.serialize(src.get()) : JsonNull.INSTANCE;
    }
}
