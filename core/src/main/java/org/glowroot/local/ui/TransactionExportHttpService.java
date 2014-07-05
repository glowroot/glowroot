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

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import com.google.common.net.MediaType;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.handler.stream.ChunkedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.local.ui.TransactionCommonService.TransactionHeader;
import org.glowroot.markers.Singleton;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Http service to export a transaction snapshot as a complete html page, bound to
 * /export/transaction. It is not bound under /backend since it is visible to users as the download
 * url for the export file.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@VisibleForTesting
@Singleton
public class TransactionExportHttpService implements HttpService {

    private static final Logger logger =
            LoggerFactory.getLogger(TransactionExportHttpService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final TransactionCommonService transactionCommonService;

    TransactionExportHttpService(TransactionCommonService transactionCommonService) {
        this.transactionCommonService = transactionCommonService;
    }

    @Override
    @Nullable
    public HttpResponse handleRequest(HttpRequest request, Channel channel) throws IOException {
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        List<String> transactionNameParameters = decoder.getParameters().get("transaction-name");
        String transactionName = null;
        if (transactionNameParameters != null) {
            transactionName = transactionNameParameters.get(0);
        }
        List<String> fromParameters = decoder.getParameters().get("from");
        if (fromParameters == null) {
            throw new IllegalArgumentException("Missing required query parameter: from");
        }
        long from = Long.parseLong(fromParameters.get(0));
        List<String> toParameters = decoder.getParameters().get("to");
        if (toParameters == null) {
            throw new IllegalArgumentException("Missing required query parameter: to");
        }
        long to = Long.parseLong(toParameters.get(0));
        TransactionHeader transactionHeader =
                transactionCommonService.getTransactionHeader(transactionName, from, to);
        TransactionProfileNode profile = null;
        if (transactionName != null) {
            profile = transactionCommonService.getProfile(transactionName, from, to, 0.001);
        }
        ChunkedInput in = getExportChunkedInput(transactionHeader, profile);
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, MediaType.ZIP.toString());
        response.headers().set("Content-Disposition",
                "attachment; filename=" + getFilename(transactionHeader) + ".zip");
        HttpServices.preventCaching(response);
        response.setChunked(true);
        channel.write(response);
        channel.write(in);
        // return null to indicate streaming
        return null;
    }

    private ChunkedInput getExportChunkedInput(TransactionHeader transactionHeader,
            @Nullable TransactionProfileNode profile) throws IOException {
        CharSource charSource = render(transactionHeader, profile);
        return ChunkedInputs.fromReaderToZipFileDownload(charSource.openStream(),
                getFilename(transactionHeader));
    }

    private static String getFilename(TransactionHeader transactionHeader) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS")
                .format(transactionHeader.getTo());
        String transactionName = transactionHeader.getTransactionName();
        if (transactionName == null) {
            return "transaction-all-" + timestamp;
        }
        String filenameSafeTransactionName = CharMatcher.inRange('a', 'z')
                .or(CharMatcher.inRange('A', 'Z')).retainFrom(transactionName);
        return "transaction-" + filenameSafeTransactionName + '-' + timestamp;
    }

    private static CharSource render(TransactionHeader transactionHeader,
            @Nullable TransactionProfileNode profile) throws IOException {
        String exportCssPlaceholder =
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"styles/export.css\">";
        String exportComponentsJsPlaceholder =
                "<script src=\"scripts/export.components.js\"></script>";
        String exportJsPlaceholder = "<script src=\"scripts/transaction-export.js\"></script>";
        String transactionPlaceholder =
                "<script type=\"text/json\" id=\"transactionJson\"></script>";
        String profilePlaceholder = "<script type=\"text/json\" id=\"profileJson\"></script>";

        String templateContent = asCharSource("transaction-export.html").read();
        Pattern pattern = Pattern.compile("(" + exportCssPlaceholder + "|"
                + exportComponentsJsPlaceholder + "|" + exportJsPlaceholder + "|"
                + transactionPlaceholder + "|" + profilePlaceholder + ")");
        Matcher matcher = pattern.matcher(templateContent);
        int curr = 0;
        List<CharSource> charSources = Lists.newArrayList();
        while (matcher.find()) {
            charSources.add(CharSource.wrap(
                    templateContent.substring(curr, matcher.start())));
            curr = matcher.end();
            String match = matcher.group();
            if (match.equals(exportCssPlaceholder)) {
                charSources.add(CharSource.wrap("<style>"));
                charSources.add(asCharSource("styles/export.css"));
                charSources.add(CharSource.wrap("</style>"));
            } else if (match.equals(exportComponentsJsPlaceholder)) {
                charSources.add(CharSource.wrap("<script>"));
                charSources.add(asCharSource("scripts/export.components.js"));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(exportJsPlaceholder)) {
                charSources.add(CharSource.wrap("<script>"));
                charSources.add(asCharSource("scripts/transaction-export.js"));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(transactionPlaceholder)) {
                charSources.add(CharSource.wrap(
                        "<script type=\"text/json\" id=\"transactionJson\">"));
                charSources.add(CharSource.wrap(mapper.writeValueAsString(transactionHeader)));
                charSources.add(CharSource.wrap("</script>"));
            } else if (match.equals(profilePlaceholder)) {
                charSources.add(CharSource.wrap(
                        "<script type=\"text/json\" id=\"profileJson\">"));
                if (profile != null) {
                    charSources.add(CharSource.wrap(mapper.writeValueAsString(profile)));
                }
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
