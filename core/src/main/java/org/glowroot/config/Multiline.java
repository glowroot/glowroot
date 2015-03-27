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
package org.glowroot.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

// for binding either string or array of strings joined into a single string
@JsonDeserialize(using = MultilineDeserializer.class)
class Multiline {

    private final String string;

    static Multiline of(String string) {
        return new Multiline(string);
    }

    Multiline(String string) {
        this.string = string;
    }

    @Override
    public String toString() {
        return string;
    }
}
