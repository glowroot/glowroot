/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.agent.stress;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CompareReportTest {

    public static void main(String[] args) throws Exception {
        shouldWriteComparisonForHttpAndH2Timings();
    }

    private static void shouldWriteComparisonForHttpAndH2Timings() throws Exception {
        File root = Files.createTempDirectory("compare-report").toFile();
        File a = new File(root, "a");
        File b = new File(root, "b");
        a.mkdirs();
        b.mkdirs();
        write(a, "fill-progress.json",
                "{\"targetGb\":1,\"dataBytes\":1073741824,\"ops\":1000,\"updatedAt\":1000000}");
        write(b, "fill-progress.json",
                "{\"targetGb\":1,\"dataBytes\":2147483648,\"ops\":2000,\"updatedAt\":2000000}");
        write(a, "timing-http.csv",
                "ts,phase,endpoint,status,latencyMs\n"
                        + "1,dual,/throughput,200,1\n"
                        + "2,dual,/throughput,200,3\n"
                        + "3,dual,/throughput,ERR,100\n");
        write(b, "timing-http.csv",
                "ts,phase,endpoint,status,latencyMs\n"
                        + "1,dual,/throughput,200,2\n"
                        + "2,dual,/throughput,200,4\n");
        write(a, "timing-h2.csv", "ts,latencyMs,sqlTruncated\n1,12,select one\n");
        write(b, "timing-h2.csv",
                "ts,latencyMs,sqlTruncated\n1,50,select slow\n2,10,select fast\n");
        File out = new File(root, "compare.md");

        CompareReport.main(new String[] {
                "--a=" + a.getPath(), "--b=" + b.getPath(), "--out=" + out.getPath()
        });

        String report = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertContains(report, "## Fill");
        assertContains(report, "## Dual HTTP");
        assertContains(report, "| /throughput | 3.0 | 3.0 | 2 | 4.0 | 4.0 | 2 |");
        assertContains(report, "## H2");
        assertContains(report, "select slow");
        assertContains(report, "## Events");
    }

    private static void write(File dir, String name, String contents) throws Exception {
        Files.write(new File(dir, name).toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertContains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("expected report to contain: " + expected + "\n" + text);
        }
    }
}
