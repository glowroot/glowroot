/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.ui;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.informantproject.api.LargeStringBuilder;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.TraceRegistry;
import org.informantproject.local.trace.StoredTrace;
import org.informantproject.local.trace.TraceDao;
import org.informantproject.local.ui.HttpServer.HttpService;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Ticker;
import com.google.common.io.CharStreams;
import com.google.common.io.Closeables;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Http service to export full trace html page. Bound to url "/trace/export" in HttpServer.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class TraceExportHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceExportHttpService.class);

    private final TraceDao traceDao;
    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    @Inject
    public TraceExportHttpService(TraceDao traceDao, TraceRegistry traceRegistry, Ticker ticker) {
        this.traceDao = traceDao;
        this.traceRegistry = traceRegistry;
        this.ticker = ticker;
    }

    public HttpResponse handleRequest(HttpRequest request) throws IOException {
        logger.debug("handleRequest()");
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String id = decoder.getParameters().get("id").get(0);
        // check active traces first to make sure that the trace is not missed if it should complete
        // after checking stored traces but before checking active traces
        CharSequence traceJson;
        Trace activeTrace = getActiveTrace(id);
        if (activeTrace != null) {
            long captureTick = ticker.read();
            LargeStringBuilder buffer = new LargeStringBuilder();
            JsonWriter jw = new JsonWriter(CharStreams.asWriter(buffer));
            Map<String, String> stackTraces = new HashMap<String, String>();
            TraceDetailJsonService.writeActiveTrace(activeTrace, stackTraces, captureTick, jw,
                    buffer);
            jw.close();
            traceJson = buffer.build();
            // TODO handle stackTraces
        } else {
            StoredTrace storedTrace = traceDao.readStoredTrace(id);
            if (storedTrace == null) {
                return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
            } else {
                LargeStringBuilder buffer = new LargeStringBuilder();
                JsonWriter jw = new JsonWriter(CharStreams.asWriter(buffer));
                TraceDetailJsonService.writeStoredTrace(storedTrace, jw, buffer);
                jw.close();
                traceJson = buffer.build();
                // TODO handle stackTraces
            }
        }
        String filename = "trace-" + id;
        String templateContent = getResourceContent(
                "org/informantproject/local/ui/trace-export.html");
        Pattern pattern = Pattern.compile("\\{\\{#include ([^}]+)\\}\\}");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(baos);
        out.putNextEntry(new ZipEntry(filename + ".html"));
        Writer writer = new OutputStreamWriter(out, Charsets.UTF_8);
        while (matcher.find()) {
            writer.append(templateContent.substring(curr, matcher.start()));
            String include = matcher.group(1);
            if (include.equals("traces")) {
                writer.append("[");
                writer.append(traceJson);
                writer.append("]");
            } else {
                writer.append(getResourceContent(include));
            }
            curr = matcher.end();
        }
        writer.append(templateContent.substring(curr));
        writer.close();

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setContent(ChannelBuffers.copiedBuffer(baos.toByteArray()));
        response.setHeader(Names.CONTENT_TYPE, "application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=" + filename + ".zip");
        return response;
    }

    private Trace getActiveTrace(String id) {
        for (Trace trace : traceRegistry.getTraces()) {
            if (trace.getId().equals(id)) {
                return trace;
            }
        }
        return null;
    }

    private String getResourceContent(String path) throws IOException {
        Reader in = new InputStreamReader(TraceExportHttpService.class.getClassLoader()
                .getResourceAsStream(path), Charsets.UTF_8);
        try {
            return CharStreams.toString(in);
        } finally {
            Closeables.closeQuietly(in);
        }
    }
}
