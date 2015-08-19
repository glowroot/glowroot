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
package org.glowroot.common.repo.helper;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.io.CharStreams;

import org.glowroot.agent.model.TimerImpl;
import org.glowroot.collector.spi.GarbageCollectionActivity;
import org.glowroot.collector.spi.ProfileNode;
import org.glowroot.collector.spi.Query;
import org.glowroot.collector.spi.ThrowableInfo;
import org.glowroot.collector.spi.TimerNode;
import org.glowroot.collector.spi.TraceTimerNode;
import org.glowroot.common.repo.TraceRepository.TraceHeader;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Traverser;

public class JsonMarshaller {

    private static final ObjectMapper mapper = ObjectMappers.create();

    static {
        SimpleModule module = new SimpleModule();
        module.setMixInAnnotation(TimerImpl.class, ImmutableXTraceTimerNode.class);
        mapper.registerModule(module);
    }

    private JsonMarshaller() {}

    // TraceHeader needs above mixin for TimerImpl in case of marshaling "pending" transaction trace
    // (one that is no longer active, but is waiting for storage)
    public static String marshal(TraceHeader traceHeader) throws IOException {
        return mapper.writeValueAsString(traceHeader);
    }

    public static String marshal(TimerNode aggregateTimer) throws IOException {
        return mapper.writeValueAsString(aggregateTimer);
    }

    public static @Nullable String marshal(
            Map<String, ? extends Collection<? extends Query>> queries)
                    throws JsonProcessingException {
        if (queries.isEmpty()) {
            return null;
        }
        return mapper.writeValueAsString(queries);
    }

    public static String marshal(ProfileNode profileNode) throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        new ProfileWriter(profileNode, jg).write();
        jg.close();
        return sb.toString();
    }

    public static @Nullable String marshalCustomAttributes(
            Map<String, ? extends Collection<String>> customAttributes) throws IOException {
        if (customAttributes.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        for (Entry<String, ? extends Collection<String>> entry : customAttributes.entrySet()) {
            jg.writeObjectField(entry.getKey(), entry.getValue());
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    public static @Nullable String marshalDetailMap(
            Map<String, ? extends /*@Nullable*/Object> detail) throws IOException {
        if (detail.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        new DetailMapWriter(jg).write(detail);
        jg.close();
        return sb.toString();
    }

    public static @Nullable String marshal(@Nullable ThrowableInfo throwable) throws IOException {
        if (throwable == null) {
            return null;
        }
        return mapper.writeValueAsString(throwable);
    }

    public static String marshal(TraceTimerNode traceTimer) throws IOException {
        return mapper.writeValueAsString(traceTimer);
    }

    public static @Nullable String marshalGcActivity(
            Map<String, ? extends GarbageCollectionActivity> gcActivity) throws IOException {
        if (gcActivity.isEmpty()) {
            return null;
        }
        return mapper.writeValueAsString(gcActivity);
    }

    // custom serializer to avoid StackOverflowError caused by default recursive algorithm
    private static class ProfileWriter extends Traverser<ProfileNode, IOException> {

        private final JsonGenerator jg;

        private ProfileWriter(ProfileNode rootNode, JsonGenerator jg) throws IOException {
            super(rootNode);
            this.jg = jg;
        }

        private void write() throws IOException {
            traverse();
        }

        @Override
        public Iterator<? extends ProfileNode> visit(ProfileNode node) throws IOException {
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
            if (node instanceof org.glowroot.common.repo.MutableProfileNode) {
                // this is only used by UI code which truncates profiles before sending them to the
                // browser
                int ellipsedSampleCount = ((org.glowroot.common.repo.MutableProfileNode) node)
                        .getEllipsedSampleCount();
                if (ellipsedSampleCount != 0) {
                    jg.writeNumberField("ellipsedSampleCount", ellipsedSampleCount);
                }
            }
            Iterator<? extends ProfileNode> childNodes = node.childNodes().iterator();
            if (childNodes.hasNext()) {
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
