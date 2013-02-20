/**
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

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.util.ByteStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

import checkers.nullness.quals.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Http service to export full trace html page.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@VisibleForTesting
public class TraceExportHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceExportHttpService.class);

    private final TraceCommonService traceCommon;

    @Inject
    TraceExportHttpService(TraceCommonService traceCommon) {
        this.traceCommon = traceCommon;
    }

    @Nullable
    public HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        String uri = request.getUri();
        String id = uri.substring(uri.lastIndexOf('/') + 1);
        logger.debug("handleRequest(): id={}", id);
        ByteStream byteStream = getExportByteStream(id);
        if (byteStream == null) {
            logger.error("no trace found for id '{}'", id);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setHeader(Names.CONTENT_TYPE, "application/zip");
        if (HttpHeaders.isKeepAlive(request)) {
            // keep alive is not supported to avoid having to calculate content length
            response.setHeader(Names.CONNECTION, "close");
        }
        response.setHeader("Content-Disposition", "attachment; filename=" + getFilename(id)
                + ".zip");
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        ChannelFuture f = channel.write(byteStream.toChunkedInput());
        f.addListener(ChannelFutureListener.CLOSE);
        // return null to indicate streaming
        return null;
    }

    @VisibleForTesting
    @Nullable
    public ByteStream getExportByteStream(String id) throws IOException {
        ByteStream traceByteStream = traceCommon.getSnapshotOrActiveJson(id, false);
        if (traceByteStream == null) {
            return null;
        }
        String templateContent = getResourceContent("io/informant/local/ui/export.html");
        Pattern pattern = Pattern.compile("\\{\\{include ([^}]+)\\}\\}");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        List<ByteStream> byteStreams = Lists.newArrayList();
        while (matcher.find()) {
            byteStreams.add(ByteStream.of(templateContent.substring(curr, matcher.start())));
            String include = matcher.group(1);
            if (include.equals("detailTrace")) {
                byteStreams.add(traceByteStream);
            } else {
                // TODO stream resource content as ByteStream (wait for guava 14 and ByteSource)
                byteStreams.add(ByteStream.of(getResourceContent(include)));
            }
            curr = matcher.end();
        }
        byteStreams.add(ByteStream.of(templateContent.substring(curr)));
        ByteStream byteStream = ByteStream.of(byteStreams);
        return new ExportByteStream(byteStream, getFilename(id));
    }

    private static String getResourceContent(String path) throws IOException {
        URL url;
        try {
            url = Resources.getResource(path);
        } catch (IllegalArgumentException e) {
            logger.error("could not find resource '{}'", path);
            return "";
        }
        return Resources.toString(url, Charsets.UTF_8);
    }

    private static String getFilename(String id) {
        return "trace-" + id;
    }

    private static class ExportByteStream extends ByteStream {

        private final ByteStream byteStream;
        private final ByteArrayOutputStream baos;
        private final ZipOutputStream zipOut;

        private ExportByteStream(ByteStream byteStream, String filename) throws IOException {
            this.byteStream = byteStream;
            baos = new ByteArrayOutputStream();
            zipOut = new ZipOutputStream(baos);
            zipOut.putNextEntry(new ZipEntry(filename + ".html"));
        }

        @Override
        public boolean hasNext() {
            return byteStream.hasNext();
        }

        @Override
        public byte[] next() throws IOException {
            byte[] next = byteStream.next();
            zipOut.write(next);
            zipOut.flush();
            if (!byteStream.hasNext()) {
                // need to close zip output stream in the final chunk
                zipOut.close();
            }
            // toByteArray returns a copy so it's ok to reset the ByteArrayOutputStream afterwards
            byte[] bytes = baos.toByteArray();
            baos.reset();
            return bytes;
        }
    }
}
