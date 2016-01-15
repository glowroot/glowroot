/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.tests;

import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

public class ProtoOptional {

    public static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }

    public static OptionalInt64 of(long value) {
        return OptionalInt64.newBuilder().setValue(value).build();
    }
}
