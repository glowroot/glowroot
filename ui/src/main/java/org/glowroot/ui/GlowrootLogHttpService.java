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
package org.glowroot.ui;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;
import com.google.common.primitives.Longs;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;

import org.glowroot.ui.HttpSessionManager.Authentication;

import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class GlowrootLogHttpService implements HttpService {

    private static final int DEFAULT_MAX_LINES = 1000;

    private static final Ordering<File> byLastModified = new Ordering<File>() {
        @Override
        public int compare(File left, File right) {
            return Longs.compare(left.lastModified(), right.lastModified());
        }
    };

    private final File logDir;

    GlowrootLogHttpService(File logDir) {
        this.logDir = logDir;
    }

    @Override
    public String getPermission() {
        return "admin:view:log";
    }

    @Override
    public @Nullable FullHttpResponse handleRequest(ChannelHandlerContext ctx, HttpRequest request,
            Authentication authentication) throws Exception {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> maxLinesParams = decoder.parameters().get("max-lines");
        if (maxLinesParams == null) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, FOUND);
            response.headers().set(HttpHeaderNames.LOCATION, "log?max-lines=" + DEFAULT_MAX_LINES);
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
            if (file.isFile() && file.getName().matches("glowroot.*\\.log")) {
                files.add(file);
            }
        }
        files = byLastModified.reverse().sortedCopy(files);
        List<String> lines = Lists.newArrayList();
        for (File file : files) {
            // logback writes logs in default charset
            List<String> olderLines = Files.readLines(file, Charset.defaultCharset());
            olderLines.addAll(lines);
            lines = olderLines;
            if (lines.size() >= maxLines) {
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
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive && !request.protocolVersion().isKeepAliveDefault()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        HttpServices.preventCaching(response);
        ctx.write(response);
        ChannelFuture future = ctx.write(ChunkedInputs.create(ChunkSource.concat(chunkSources)));
        HttpServices.addErrorListener(future);
        if (!keepAlive) {
            HttpServices.addCloseListener(future);
        }
        // return null to indicate streaming
        return null;
    }
}
