/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.common.config;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

import org.glowroot.common.util.Versions;

@Value.Immutable
public abstract class HttpProxyConfig {

    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public String host() {
        return "";
    }

    // need to write zero since it is treated different from null
    // (although in this case zero is not a valid value)
    @JsonInclude(Include.NON_NULL)
    public abstract @Nullable Integer port();

    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public String username() {
        return "";
    }

    @Value.Default
    @JsonInclude(Include.NON_EMPTY)
    public String password() {
        return "";
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getJsonVersion(this);
    }
}
