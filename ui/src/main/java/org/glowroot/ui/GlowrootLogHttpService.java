/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.ui;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;
import com.google.common.net.MediaType;
import com.google.common.primitives.Longs;
import io.netty.handler.codec.http.HttpHeaderNames;

import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;

import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class GlowrootLogHttpService implements HttpService {

    private static final int DEFAULT_MAX_LINES = 1000;

    private static final Ordering<File> byLastModified = new Ordering<File>() {
        @Override
        public int compare(File left, File right) {
            return Longs.compare(left.lastModified(), right.lastModified());
        }
    };

    private final File logDir;
    private final Pattern logFileNamePattern;

    GlowrootLogHttpService(File logDir, Pattern logFileNamePattern) {
        this.logDir = logDir;
        this.logFileNamePattern = logFileNamePattern;
    }

    @Override
    public String getPermission() {
        return "admin:view:log";
    }

    @Override
    public CommonResponse handleRequest(CommonRequest request, Authentication authentication)
            throws Exception {
        List<String> maxLinesParams = request.getParameters("max-lines");
        if (maxLinesParams.isEmpty()) {
            CommonResponse response = new CommonResponse(FOUND);
            response.setHeader(HttpHeaderNames.LOCATION, "log?max-lines=" + DEFAULT_MAX_LINES);
            return response;
        }
        int maxLines = Integer.parseInt(maxLinesParams.get(0));

        File[] list = logDir.listFiles();
        if (list == null) {
            throw new IllegalStateException(
                    "Could not list directory: " + logDir.getAbsolutePath());
        }
        List<File> files = Lists.newArrayList();
        for (File file : list) {
            if (file.isFile() && logFileNamePattern.matcher(file.getName()).matches()) {
                files.add(file);
            }
        }
        files = byLastModified.reverse().sortedCopy(files);
        List<String> lines = Lists.newArrayList();
        for (File file : files) {
            // don't read entire file into memory at once, even though rollover may be 10mb, a flood
            // of logging can create a much much much larger file before rollover occurs
            // (see ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy call to isTooSoon())

            // logback writes logs in default charset
            // "+ 1" is to read extra line to know whether to add "[earlier log entries truncated]"
            Collection<String> olderLines = Files.readLines(file, Charset.defaultCharset(),
                    new ReadLastNLines(maxLines + 1 - lines.size()));
            lines.addAll(0, olderLines);
            if (lines.size() > maxLines) {
                break;
            }
        }
        List<ChunkSource> chunkSources = Lists.newArrayList();
        if (lines.size() > maxLines) {
            // return last 'maxLines' lines from aggregated log files
            lines = lines.subList(lines.size() - maxLines, lines.size());
            chunkSources.add(ChunkSource.wrap("[earlier log entries truncated]\n\n"));
        }
        for (String line : lines) {
            chunkSources.add(ChunkSource.wrap(line + '\n'));
        }
        return new CommonResponse(OK, MediaType.PLAIN_TEXT_UTF_8, ChunkSource.concat(chunkSources));
    }

    private static class ReadLastNLines implements LineProcessor<Collection<String>> {

        private final Collection<String> queue;

        private ReadLastNLines(int nLines) {
            queue = EvictingQueue.create(nLines);
        }

        @Override
        public boolean processLine(String line) {
            queue.add(line);
            return true;
        }

        @Override
        public Collection<String> getResult() {
            return queue;
        }
    }
}
