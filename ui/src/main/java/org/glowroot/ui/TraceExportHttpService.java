/*
 * Copyright 2011-2016 the original author or authors.
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

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.ui.TraceCommonService.TraceExport;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class TraceExportHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceExportHttpService.class);

    private final TraceCommonService traceCommonService;
    private final String version;

    TraceExportHttpService(TraceCommonService traceCommonService, String version) {
        this.traceCommonService = traceCommonService;
        this.version = version;
    }

    @Override
    public String getPermission() {
        // see special case for "agent:trace" permission in Authentication.isPermitted()
        return "agent:trace";
    }

    @Override
    public @Nullable FullHttpResponse handleRequest(ChannelHandlerContext ctx, HttpRequest request,
            Authentication authentication) throws Exception {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> agentIds = decoder.parameters().get("agent-id");
        if (agentIds == null) {
            agentIds = ImmutableList.of("");
        }
        String agentId = agentIds.get(0);
        List<String> traceIds = decoder.parameters().get("trace-id");
        checkNotNull(traceIds, "Missing trace id in query string: %s", request.uri());
        String traceId = traceIds.get(0);
        // check-live-traces is an optimization so glowroot server only has to check with remote
        // agents when necessary
        List<String> checkLiveTracesParams = decoder.parameters().get("check-live-traces");
        boolean checkLiveTraces = false;
        if (checkLiveTracesParams != null && !checkLiveTracesParams.isEmpty()) {
            checkLiveTraces = Boolean.parseBoolean(checkLiveTracesParams.get(0));
        }
        logger.debug("handleRequest(): agentId={}, traceId={}, checkLiveTraces={}", agentId,
                traceId, checkLiveTraces);
        TraceExport traceExport = traceCommonService.getExport(agentId, traceId, checkLiveTraces);
        if (traceExport == null) {
            logger.warn("no trace found for id: {}", traceId);
            return new DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        ChunkedInput<HttpContent> in = getExportChunkedInput(traceExport);
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, MediaType.ZIP.toString());
        response.headers().set("Content-Disposition",
                "attachment; filename=" + traceExport.fileName() + ".zip");
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive && !request.protocolVersion().isKeepAliveDefault()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        HttpServices.preventCaching(response);
        ctx.write(response);
        ChannelFuture future = ctx.write(in);
        HttpServices.addErrorListener(future);
        if (!keepAlive) {
            HttpServices.addCloseListener(future);
        }
        // return null to indicate streaming
        return null;
    }

    private ChunkedInput<HttpContent> getExportChunkedInput(TraceExport traceExport)
            throws IOException {
        ChunkSource chunkSource = render(traceExport);
        return ChunkedInputs.fromChunkSourceToZipFileDownload(chunkSource, traceExport.fileName());
    }

    private ChunkSource render(TraceExport traceExport) throws IOException {
        String htmlStartTag = "<html>";
        String exportCssPlaceholder = "<link rel=\"stylesheet\" href=\"styles/export.css\">";
        String exportJsPlaceholder = "<script src=\"scripts/export.js\"></script>";
        String headerPlaceholder = "<script type=\"text/json\" id=\"headerJson\"></script>";
        String entriesPlaceholder = "<script type=\"text/json\" id=\"entriesJson\"></script>";
        String mainThreadProfilePlaceholder =
                "<script type=\"text/json\" id=\"mainThreadProfileJson\"></script>";
        String auxThreadProfilePlaceholder =
                "<script type=\"text/json\" id=\"auxThreadProfileJson\"></script>";
        String footerMessagePlaceholder = "<span id=\"footerMessage\"></span>";

        String templateContent = asCharSource("trace-export.html").read();
        Pattern pattern = Pattern.compile("(" + htmlStartTag + "|" + exportCssPlaceholder + "|"
                + exportJsPlaceholder + "|" + headerPlaceholder + "|" + entriesPlaceholder + "|"
                + mainThreadProfilePlaceholder + "|" + auxThreadProfilePlaceholder + "|"
                + footerMessagePlaceholder + ")");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        List<ChunkSource> chunkSources = Lists.newArrayList();
        while (matcher.find()) {
            chunkSources.add(ChunkSource.wrap(templateContent.substring(curr, matcher.start())));
            curr = matcher.end();
            String match = matcher.group();
            if (match.equals(htmlStartTag)) {
                // Need to add "Mark of the Web" for IE, otherwise IE won't run javascript
                // see http://msdn.microsoft.com/en-us/library/ms537628(v=vs.85).aspx
                chunkSources.add(
                        ChunkSource.wrap("<!-- saved from url=(0014)about:internet -->\r\n<html>"));
            } else if (match.equals(exportCssPlaceholder)) {
                chunkSources.add(ChunkSource.wrap("<style>"));
                chunkSources.add(asChunkSource("styles/export.css"));
                chunkSources.add(ChunkSource.wrap("</style>"));
            } else if (match.equals(exportJsPlaceholder)) {
                chunkSources.add(ChunkSource.wrap("<script>"));
                chunkSources.add(asChunkSource("scripts/export.js"));
                chunkSources.add(ChunkSource.wrap("</script>"));
            } else if (match.equals(headerPlaceholder)) {
                chunkSources.add(ChunkSource.wrap("<script type=\"text/json\" id=\"headerJson\">"));
                chunkSources.add(ChunkSource.wrap(traceExport.headerJson()));
                chunkSources.add(ChunkSource.wrap("</script>"));
            } else if (match.equals(entriesPlaceholder)) {
                chunkSources
                        .add(ChunkSource.wrap("<script type=\"text/json\" id=\"entriesJson\">"));
                String entriesJson = traceExport.entriesJson();
                if (entriesJson != null) {
                    chunkSources.add(ChunkSource.wrap(entriesJson));
                }
                chunkSources.add(ChunkSource.wrap("</script>"));
            } else if (match.equals(mainThreadProfilePlaceholder)) {
                chunkSources.add(ChunkSource
                        .wrap("<script type=\"text/json\" id=\"mainThreadProfileJson\">"));
                String mainThreadProfileJson = traceExport.mainThreadProfileJson();
                if (mainThreadProfileJson != null) {
                    chunkSources.add(ChunkSource.wrap(mainThreadProfileJson));
                }
                chunkSources.add(ChunkSource.wrap("</script>"));
            } else if (match.equals(auxThreadProfilePlaceholder)) {
                chunkSources.add(ChunkSource
                        .wrap("<script type=\"text/json\" id=\"auxThreadProfileJson\">"));
                String auxThreadProfileJson = traceExport.auxThreadProfileJson();
                if (auxThreadProfileJson != null) {
                    chunkSources.add(ChunkSource.wrap(auxThreadProfileJson));
                }
                chunkSources.add(ChunkSource.wrap("</script>"));
            } else if (match.equals(footerMessagePlaceholder)) {
                chunkSources.add(ChunkSource.wrap("Glowroot version " + version));
            } else {
                logger.error("unexpected match: {}", match);
            }
        }
        chunkSources.add(ChunkSource.wrap(templateContent.substring(curr)));
        return ChunkSource.concat(chunkSources);
    }

    private static ChunkSource asChunkSource(String exportResourceName) {
        return ChunkSource.from(asCharSource(exportResourceName));
    }

    private static CharSource asCharSource(String exportResourceName) {
        URL url = Resources.getResource("org/glowroot/ui/export-dist/" + exportResourceName);
        return Resources.asCharSource(url, Charsets.UTF_8);
    }
}
