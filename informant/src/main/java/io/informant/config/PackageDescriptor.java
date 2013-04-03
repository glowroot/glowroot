/**
 * Copyright 2013 the original author or authors.
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
package io.informant.config;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import checkers.igj.quals.Immutable;
import checkers.igj.quals.ReadOnly;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import io.informant.common.ObjectMappers;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class PackageDescriptor {

    private final ImmutableList<PluginDescriptor> plugins;

    @JsonCreator
    public PackageDescriptor(@JsonProperty("plugins") @ReadOnly List<PluginDescriptor> plugins) {
        this.plugins = ImmutableList.copyOf(plugins);
    }

    public ImmutableList<PluginDescriptor> getPlugins() {
        return plugins;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("plugins", plugins)
                .toString();
    }

    // only used by packager-maven-plugin, placed in informant to avoid shading issues
    public void writeValue(OutputStream out) throws IOException {
        ObjectMapper mapper = ObjectMappers.create().enable(SerializationFeature.INDENT_OUTPUT);
        // disable closing since closing jarOut needs to be managed externally
        mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
        mapper.writeValue(out, this);
    }
}
