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
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

final class FillProgress {

    static final class Snapshot {
        final double targetGb;
        final long dataBytes;
        final long ops;
        final long updatedAt;

        Snapshot(double targetGb, long dataBytes, long ops, long updatedAt) {
            this.targetGb = targetGb;
            this.dataBytes = dataBytes;
            this.ops = ops;
            this.updatedAt = updatedAt;
        }

        boolean reachedTarget() {
            return dataBytes >= (long) (targetGb * 1024L * 1024L * 1024L);
        }
    }

    static long directorySizeBytes(File dir) {
        if (dir == null || !dir.exists()) {
            return 0L;
        }
        long total = 0L;
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0L;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                total += directorySizeBytes(f);
            } else {
                total += f.length();
            }
        }
        return total;
    }

    static void write(File progressFile, Snapshot snapshot) throws IOException {
        File parent = progressFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        String json = String.format(Locale.US,
                "{\"targetGb\":%.3f,\"dataBytes\":%d,\"ops\":%d,\"updatedAt\":%d}%n",
                snapshot.targetGb, snapshot.dataBytes, snapshot.ops, snapshot.updatedAt);
        try (FileWriter writer = new FileWriter(progressFile)) {
            writer.write(json);
        }
    }

    static Snapshot readOrNull(File progressFile) throws IOException {
        if (!progressFile.exists()) {
            return null;
        }
        String raw = new String(Files.readAllBytes(progressFile.toPath()), StandardCharsets.UTF_8).trim();
        return new Snapshot(
                parseDoubleField(raw, "targetGb"),
                parseLongField(raw, "dataBytes"),
                parseLongField(raw, "ops"),
                parseLongField(raw, "updatedAt"));
    }

    private static double parseDoubleField(String json, String name) {
        String key = "\"" + name + "\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return 0;
        }
        int start = i + key.length();
        int end = start;
        while (end < json.length() && "0123456789.+-eE".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    private static long parseLongField(String json, String name) {
        return (long) parseDoubleField(json, name);
    }

    private FillProgress() {}
}
