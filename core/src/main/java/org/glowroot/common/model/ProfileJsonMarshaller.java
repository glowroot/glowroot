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
package org.glowroot.common.model;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;

import org.glowroot.collector.spi.ProfileNode;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Traverser;

public class ProfileJsonMarshaller {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private ProfileJsonMarshaller() {}

    public static String marshal(ProfileNode profileNode) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        new ProfileWriter(profileNode, jg).traverse();
        jg.close();
        return sb.toString();
    }

    // custom serializer to avoid StackOverflowError caused by default recursive algorithm
    private static class ProfileWriter extends Traverser<ProfileNode, IOException> {

        private final JsonGenerator jg;

        private ProfileWriter(ProfileNode rootNode, JsonGenerator jg) throws IOException {
            super(rootNode);
            this.jg = jg;
        }

        @Override
        public Collection<? extends ProfileNode> visit(ProfileNode node) throws IOException {
            jg.writeStartObject();
            jg.writeObjectField("stackTraceElement", node.stackTraceElement());
            String leafThreadState = node.leafThreadState();
            if (leafThreadState != null) {
                jg.writeStringField("leafThreadState", leafThreadState);
            }
            jg.writeNumberField("sampleCount", node.sampleCount());
            Iterator<String> timerNames = node.timerNames().iterator();
            if (timerNames.hasNext()) {
                jg.writeArrayFieldStart("timerNames");
                while (timerNames.hasNext()) {
                    jg.writeString(timerNames.next());
                }
                jg.writeEndArray();
            }
            if (node instanceof org.glowroot.common.model.MutableProfileNode) {
                // this is only used by UI code which truncates profiles before sending them to the
                // browser
                int ellipsedSampleCount = ((org.glowroot.common.model.MutableProfileNode) node)
                        .getEllipsedSampleCount();
                if (ellipsedSampleCount != 0) {
                    jg.writeNumberField("ellipsedSampleCount", ellipsedSampleCount);
                }
            }
            Collection<? extends ProfileNode> childNodes = node.childNodes();
            if (!childNodes.isEmpty()) {
                jg.writeArrayFieldStart("childNodes");
            }
            return childNodes;
        }

        @Override
        public void revisitAfterChildren(ProfileNode node) throws IOException {
            if (!node.childNodes().isEmpty()) {
                jg.writeEndArray();
            }
            jg.writeEndObject();
        }
    }
}
