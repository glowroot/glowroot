/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import checkers.nullness.quals.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.CharStreams2;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.Singleton;

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

    @Nullable
    public HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        String uri = request.getUri();
        String id = uri.substring(uri.lastIndexOf('/') + 1);
        logger.debug("handleRequest(): id={}", id);
        ChunkedInput in = getExportChunkedInput(id);
        if (in == null) {
            logger.warn("no trace found for id: {}", id);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setHeader(CONTENT_TYPE, MediaType.ZIP.toString());
        response.setHeader("Content-Disposition", "attachment; filename=" + getFilename(id)
                + ".zip");
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        channel.write(in);
        // return null to indicate streaming
        return null;
    }

    @Nullable
    private ExportChunkedInput getExportChunkedInput(String id) throws IOException {
        CharSource traceCharSource =
                traceCommonService.createCharSourceForSnapshotOrActiveTrace(id, false);
        if (traceCharSource == null) {
            return null;
        }
        CharSource charSource = render(traceCharSource);
        return new ExportChunkedInput(charSource.openStream(), getFilename(id));
    }

    // this method exists because tests cannot use (sometimes) shaded netty ChunkedInput
    @OnlyUsedByTests
    public byte[] getExportBytes(String id) throws IOException {
        ExportChunkedInput chunkedInput = getExportChunkedInput(id);
        if (chunkedInput == null) {
            throw new IllegalStateException("No trace found for id '" + id + "'");
        }
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

    private static String getFilename(String id) {
        return "trace-" + id;
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
            charSources.add(CharStreams.asCharSource(
                    templateContent.substring(curr, matcher.start())));
            curr = matcher.end();
            String match = matcher.group();
            if (match.equals(exportCss)) {
                charSources.add(CharStreams.asCharSource("<style>"));
                charSources.add(asCharSource("styles/export.css"));
                charSources.add(CharStreams.asCharSource("</style>"));
            } else if (match.equals(exportComponentsJs)) {
                charSources.add(CharStreams.asCharSource("<script>"));
                charSources.add(asCharSource("scripts/export.components.js"));
                charSources.add(CharStreams.asCharSource("</script>"));
            } else if (match.equals(exportJs)) {
                charSources.add(CharStreams.asCharSource("<script>"));
                charSources.add(asCharSource("scripts/export.js"));
                charSources.add(CharStreams.asCharSource("</script>"));
            } else if (match.equals(detailTrace)) {
                charSources.add(CharStreams.asCharSource(
                        "<script type=\"text/json\" id=\"detailTraceJson\">"));
                charSources.add(traceCharSource);
                charSources.add(CharStreams.asCharSource("</script>"));
            } else {
                logger.error("unexpected match: {}", match);
            }
        }
        charSources.add(CharStreams.asCharSource(templateContent.substring(curr)));
        return CharStreams2.join(charSources);
    }

    private static CharSource asCharSource(String exportResourceName) {
        URL url = Resources.getResource("io/informant/local/ui/export-dist/" + exportResourceName);
        return Resources.asCharSource(url, Charsets.UTF_8);
    }

    private static class ExportChunkedInput implements ChunkedInput {

        private static final int CHUNK_SIZE = 8192;

        private final PushbackReader reader;
        private final ByteArrayOutputStream baos;
        private final Writer zipWriter;
        // need lots more chars to end up with compressed chunk of given size
        private final char[] buffer = new char[8 * CHUNK_SIZE];

        private boolean hasSentTerminatingChunk;

        private ExportChunkedInput(Reader reader, String filename) throws IOException {
            this.reader = new PushbackReader(reader);
            // write to baos until size >= CHUNK_SIZE, so give it a little extra room
            baos = new ByteArrayOutputStream(2 * CHUNK_SIZE);
            ZipOutputStream zipOut = new ZipOutputStream(baos);
            zipOut.putNextEntry(new ZipEntry(filename + ".html"));
            zipWriter = new OutputStreamWriter(zipOut, Charsets.UTF_8);
        }

        public boolean hasNextChunk() {
            return !hasSentTerminatingChunk;
        }

        @Nullable
        public Object nextChunk() throws IOException {
            if (hasMoreBytes()) {
                return readNextChunk();
            } else if (!hasSentTerminatingChunk) {
                // chunked transfer encoding must be terminated by a final chunk of length zero
                hasSentTerminatingChunk = true;
                return new DefaultHttpChunk(ChannelBuffers.EMPTY_BUFFER);
            } else {
                return null;
            }
        }

        public boolean isEndOfInput() {
            return hasSentTerminatingChunk;
        }

        public void close() {}

        private boolean hasMoreBytes() throws IOException {
            int b = reader.read();
            if (b < 0) {
                return false;
            } else {
                reader.unread(b);
                return true;
            }
        }

        private Object readNextChunk() throws IOException {
            int total = 0;
            while (true) {
                int n = reader.read(buffer, total, buffer.length - total);
                if (n == -1) {
                    break;
                }
                total += n;
                if (total == buffer.length) {
                    break;
                }
            }
            // no need to flush, there's no buffering except in ZipOutputStream, and that buffering
            // is for compression and doesn't respond to flush() anyways
            zipWriter.write(buffer, 0, total);
            if (baos.size() < CHUNK_SIZE && hasMoreBytes()) {
                return readNextChunk();
            }
            if (!hasMoreBytes()) {
                // write remaining compressed data
                zipWriter.close();
            }
            // toByteArray returns a copy so it's ok to reset the ByteArrayOutputStream afterwards
            byte[] bytes = baos.toByteArray();
            baos.reset();
            return new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(bytes));
        }
    }
}
