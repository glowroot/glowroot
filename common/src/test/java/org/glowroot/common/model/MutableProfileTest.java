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
package org.glowroot.common.model;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MutableProfileTest {

    @Test
    public void testSingleStackTrace() throws IOException {
        // given
        MutableProfile profile = new MutableProfile();
        List<StackTraceElement> stackTraceElements = Lists.newArrayList();
        stackTraceElements.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 123));
        stackTraceElements.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 456));
        stackTraceElements.add(new StackTraceElement("xx.yy.zz.Main", "main", "Main.java", 789));
        // when
        profile.merge(stackTraceElements, Thread.State.RUNNABLE);
        // then
        assertThat(profile.toJson()).isEqualTo(("{"
                + "  \"unfilteredSampleCount\": 1,"
                + "  \"rootNodes\": ["
                + "    {"
                + "      \"stackTraceElement\": \"xx.yy.zz.Main.main(Main.java:789)\","
                + "      \"sampleCount\": 1,"
                + "      \"childNodes\": ["
                + "        {"
                + "          \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:456)\","
                + "          \"sampleCount\": 1,"
                + "          \"childNodes\": ["
                + "            {"
                + "              \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:123)\","
                + "              \"leafThreadState\": \"RUNNABLE\","
                + "              \"sampleCount\": 1"
                + "            }"
                + "          ]"
                + "        }"
                + "      ]"
                + "    }"
                + "  ]"
                + "}").replace(" ", ""));
    }

    @Test
    public void testMerging() throws IOException {
        // given
        MutableProfile profile = new MutableProfile();
        List<StackTraceElement> stackTraceElements = Lists.newArrayList();
        stackTraceElements.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 123));
        stackTraceElements.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 456));
        stackTraceElements.add(new StackTraceElement("xx.yy.zz.Main", "main", "Main.java", 789));
        // when
        profile.merge(stackTraceElements, Thread.State.RUNNABLE);
        profile.merge(stackTraceElements, Thread.State.BLOCKED);
        // then
        assertThat(profile.toJson()).isEqualTo(("{"
                + "  \"unfilteredSampleCount\": 2,"
                + "  \"rootNodes\": ["
                + "    {"
                + "      \"stackTraceElement\": \"xx.yy.zz.Main.main(Main.java:789)\","
                + "      \"sampleCount\": 2,"
                + "      \"childNodes\": ["
                + "        {"
                + "          \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:456)\","
                + "          \"sampleCount\": 2,"
                + "          \"childNodes\": ["
                + "            {"
                + "              \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:123)\","
                + "              \"leafThreadState\": \"RUNNABLE\","
                + "              \"sampleCount\": 1"
                + "            },"
                + "            {"
                + "              \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:123)\","
                + "              \"leafThreadState\": \"BLOCKED\","
                + "              \"sampleCount\": 1"
                + "            }"
                + "          ]"
                + "        }"
                + "      ]"
                + "    }"
                + "  ]"
                + "}").replace(" ", ""));
    }

    @Test
    public void testMultipleRootNodes() throws IOException {
        // given
        MutableProfile profile = new MutableProfile();
        List<StackTraceElement> stackTraceElements1 = Lists.newArrayList();
        stackTraceElements1.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 123));
        stackTraceElements1.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 456));
        stackTraceElements1.add(new StackTraceElement("xx.yy.zz.Main", "main", "Main.java", 789));
        List<StackTraceElement> stackTraceElements2 = Lists.newArrayList();
        stackTraceElements2.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 123));
        stackTraceElements2.add(new StackTraceElement("aa.bb.cc.Def", "ghi", "Def.java", 456));
        stackTraceElements2.add(new StackTraceElement("xx.yy.zz.Main", "main2", "Main.java", 789));
        // when
        profile.merge(stackTraceElements1, Thread.State.RUNNABLE);
        profile.merge(stackTraceElements2, Thread.State.RUNNABLE);
        // then
        assertThat(profile.toJson()).isEqualTo(("{"
                + "  \"unfilteredSampleCount\": 2,"
                + "  \"rootNodes\": ["
                + "    {"
                + "      \"stackTraceElement\": \"xx.yy.zz.Main.main(Main.java:789)\","
                + "      \"sampleCount\": 1,"
                + "      \"childNodes\": ["
                + "        {"
                + "          \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:456)\","
                + "          \"sampleCount\": 1,"
                + "          \"childNodes\": ["
                + "            {"
                + "              \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:123)\","
                + "              \"leafThreadState\": \"RUNNABLE\","
                + "              \"sampleCount\": 1"
                + "            }"
                + "          ]"
                + "        }"
                + "      ]"
                + "    },"
                + "    {"
                + "      \"stackTraceElement\": \"xx.yy.zz.Main.main2(Main.java:789)\","
                + "      \"sampleCount\": 1,"
                + "      \"childNodes\": ["
                + "        {"
                + "          \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:456)\","
                + "          \"sampleCount\": 1,"
                + "          \"childNodes\": ["
                + "            {"
                + "              \"stackTraceElement\": \"aa.bb.cc.Def.ghi(Def.java:123)\","
                + "              \"leafThreadState\": \"RUNNABLE\","
                + "              \"sampleCount\": 1"
                + "            }"
                + "          ]"
                + "        }"
                + "      ]"
                + "    }"
                + "  ]"
                + "}").replace(" ", ""));
    }

    // this is helpful when building tests
    @SuppressWarnings("unused")
    private static void prettyPrint(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);

        CustomPrettyPrinter prettyPrinter = new CustomPrettyPrinter();
        prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb))
                .setPrettyPrinter(prettyPrinter);
        jg.writeTree(node);
        jg.close();

        System.out.println(sb.toString().replace("\"", "\\\"").replace(DefaultIndenter.SYS_LF,
                "\"" + DefaultIndenter.SYS_LF + " + \""));
    }

    @SuppressWarnings("serial")
    private static class CustomPrettyPrinter extends DefaultPrettyPrinter {

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator jg) throws IOException {
            jg.writeRaw(": ");
        }
    }
}
