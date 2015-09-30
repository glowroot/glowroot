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
package org.glowroot.common.util;

import java.lang.annotation.Target;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;
import org.immutables.value.Value.Style.ImplementationVisibility;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;

public class Styles {

    private Styles() {}

    @Target({PACKAGE, TYPE})
    @Value.Style(from = "copyFrom")
    @JsonSerialize
    public @interface Standard {}

    @Target({PACKAGE, TYPE})
    @Value.Style(from = "copyFrom", visibility = ImplementationVisibility.PRIVATE)
    @JsonSerialize
    public @interface Private {}

    @Target({PACKAGE, TYPE})
    @Value.Style(from = "copyFrom", allParameters = true)
    @JsonSerialize
    public @interface AllParameters {}
}
