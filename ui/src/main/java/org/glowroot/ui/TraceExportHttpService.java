/*
 * Copyright 2011-2017 the original author or authors.
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

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;
import org.glowroot.ui.HttpSessionManager.Authentication;
import org.glowroot.ui.TraceCommonService.TraceExport;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

class TraceExportHttpService implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(TraceExportHttpService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("audit");

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
    public CommonResponse handleRequest(CommonRequest request, Authentication authentication)
            throws Exception {
        auditLogger.info("{} - GET {}", authentication.caseAmbiguousUsername(), request.getUri());
        List<String> agentIds = request.getParameters("agent-id");
        String agentId = agentIds.isEmpty() ? "" : agentIds.get(0);
        List<String> traceIds = request.getParameters("trace-id");
        checkState(!traceIds.isEmpty(), "Missing trace id in query string: %s", request.getUri());
        String traceId = traceIds.get(0);
        // check-live-traces is an optimization so the central collector only has to check with
        // remote agents when necessary
        List<String> checkLiveTracesParams = request.getParameters("check-live-traces");
        boolean checkLiveTraces = !checkLiveTracesParams.isEmpty()
                && Boolean.parseBoolean(checkLiveTracesParams.get(0));
        logger.debug("handleRequest(): agentId={}, traceId={}, checkLiveTraces={}", agentId,
                traceId, checkLiveTraces);
        TraceExport traceExport = traceCommonService.getExport(agentId, traceId, checkLiveTraces);
        if (traceExport == null) {
            logger.warn("no trace found for id: {}", traceId);
            return new CommonResponse(NOT_FOUND);
        }
        ChunkSource chunkSource = render(traceExport);
        CommonResponse response = new CommonResponse(OK, MediaType.ZIP, chunkSource);
        response.setZipFileName(traceExport.fileName());
        response.setHeader("Content-Disposition",
                "attachment; filename=" + traceExport.fileName() + ".zip");
        return response;
    }

    private ChunkSource render(TraceExport traceExport) throws IOException {
        String htmlStartTag = "<html>";
        String exportCssPlaceholder = "<link rel=\"stylesheet\" href=\"styles/export.css\">";
        String exportJsPlaceholder = "<script src=\"scripts/export.js\"></script>";
        String headerPlaceholder = "<script type=\"text/json\" id=\"headerJson\"></script>";
        String entriesPlaceholder = "<script type=\"text/json\" id=\"entriesJson\"></script>";
        String sharedQueryTextsPlaceholder =
                "<script type=\"text/json\" id=\"sharedQueryTextsJson\"></script>";
        String mainThreadProfilePlaceholder =
                "<script type=\"text/json\" id=\"mainThreadProfileJson\"></script>";
        String auxThreadProfilePlaceholder =
                "<script type=\"text/json\" id=\"auxThreadProfileJson\"></script>";
        String footerMessagePlaceholder = "<span id=\"footerMessage\"></span>";

        String templateContent = asCharSource("trace-export.html").read();
        Pattern pattern = Pattern.compile("(" + htmlStartTag + "|" + exportCssPlaceholder + "|"
                + exportJsPlaceholder + "|" + headerPlaceholder + "|" + entriesPlaceholder + "|"
                + sharedQueryTextsPlaceholder + "|" + mainThreadProfilePlaceholder + "|"
                + auxThreadProfilePlaceholder + "|" + footerMessagePlaceholder + ")");
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
            } else if (match.equals(sharedQueryTextsPlaceholder)) {
                chunkSources.add(ChunkSource
                        .wrap("<script type=\"text/json\" id=\"sharedQueryTextsJson\">"));
                String sharedQueryTextsJson = traceExport.sharedQueryTextsJson();
                if (sharedQueryTextsJson != null) {
                    chunkSources.add(ChunkSource.wrap(sharedQueryTextsJson));
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
        return ChunkSource.create(asCharSource(exportResourceName));
    }

    private static CharSource asCharSource(String exportResourceName) {
        URL url = TraceExportHttpService.class
                .getResource("/org/glowroot/ui/export-dist/" + exportResourceName);
        return Resources.asCharSource(checkNotNull(url), Charsets.UTF_8);
    }
}
