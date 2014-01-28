/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import checkers.nullness.quals.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.collector.Snapshot;
import org.glowroot.collector.SnapshotWriter;
import org.glowroot.markers.OnlyUsedByTests;
import org.glowroot.markers.Singleton;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Http service to export a trace snapshot as a complete html page, bound to /export. It is not
 * bound under /backend since it is visible to users as the download url for the export file.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@Singleton
public class TraceExportHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceExportHttpService.class);

    private final TraceCommonService traceCommonService;

    TraceExportHttpService(TraceCommonService traceCommonService) {
        this.traceCommonService = traceCommonService;
    }

    @Override
    @Nullable
    public HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        String uri = request.getUri();
        String id = uri.substring(uri.lastIndexOf('/') + 1);
        logger.debug("handleRequest(): id={}", id);
        Snapshot snapshot = traceCommonService.getSnapshot(id, false);
        if (snapshot == null) {
            logger.warn("no trace found for id: {}", id);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        ChunkedInput in = getExportChunkedInput(snapshot);
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, MediaType.ZIP.toString());
        response.headers().set("Content-Disposition",
                "attachment; filename=" + getFilename(snapshot) + ".zip");
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        channel.write(in);
        // return null to indicate streaming
        return null;
    }

    private ChunkedInput getExportChunkedInput(Snapshot snapshot) throws IOException {
        CharSource traceCharSource = SnapshotWriter.toCharSource(snapshot, false);
        CharSource charSource = render(traceCharSource);
        return ChunkedInputs.fromReaderToZipFileDownload(charSource.openStream(),
                getFilename(snapshot));
    }

    // this method exists because tests cannot use (sometimes) shaded netty ChunkedInput
    @OnlyUsedByTests
    public byte[] getExportBytes(String id) throws Exception {
        Snapshot snapshot = traceCommonService.getSnapshot(id, false);
        if (snapshot == null) {
            throw new IllegalStateException("No trace found for id '" + id + "'");
        }
        ChunkedInput chunkedInput = getExportChunkedInput(snapshot);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
        while (chunkedInput.hasNextChunk()) {
            DefaultHttpChunk chunk = (DefaultHttpChunk) chunkedInput.nextChunk();
            if (chunk != null) {
                ChannelBuffer content = chunk.getContent();
                byte[] bytes = new byte[content.readableBytes()];
                content.readBytes(bytes);
                baos.write(bytes);
            }
        }
        return baos.toByteArray();
    }

    private static String getFilename(Snapshot snapshot) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS")
                .format(snapshot.getStartTime());
        return "trace-" + timestamp;
    }

    private static CharSource render(CharSource traceCharSource) throws IOException {
        final String exportCss =
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"styles/export.css\">";
        final String exportComponentsJs = "<script src=\"scripts/export.components.js\"></script>";
        final String exportJs = "<script src=\"scripts/export.js\"></script>";
        final String detailTrace = "<script type=\"text/json\" id=\"detailTraceJson\"></script>";

        String templateContent = asCharSource("export.html").read();
        Pattern pattern = Pattern.compile("(" + exportCss + "|" + exportComponentsJs + "|"
                + exportJs + "|" + detailTrace + ")");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        List<CharSource> charSources = Lists.newArrayList();
        while (matcher.find()) {
            charSources.add(CharSource.wrap(
                    templateContent.substring(curr, matcher.start())));
            curr = matcher.end();
            String match = matcher.group();
            if (match.equals(exportCss)) {
                charSources.add(CharSource.wrap("<style>"));
                charSources.add(asCharSource("styles/export.css"));
                charSources.add(CharSource.wrap("</style>"));
            } else if (match.equals(exportComponentsJs)) {
                charSources.add(CharSource.wrap("<script>"));
                charSources.add(asCharSource("scripts/export.components.js"));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(exportJs)) {
                charSources.add(CharSource.wrap("<script>"));
                charSources.add(asCharSource("scripts/export.js"));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(detailTrace)) {
                charSources.add(CharSource.wrap(
                        "<script type=\"text/json\" id=\"detailTraceJson\">"));
                charSources.add(traceCharSource);
                charSources.add(CharSource.wrap("</script>"));
            } else {
                logger.error("unexpected match: {}", match);
            }
        }
        charSources.add(CharSource.wrap(templateContent.substring(curr)));
        return CharSource.concat(charSources);
    }

    private static CharSource asCharSource(String exportResourceName) {
        URL url = Resources.getResource("org/glowroot/local/ui/export-dist/" + exportResourceName);
        return Resources.asCharSource(url, Charsets.UTF_8);
    }
}
