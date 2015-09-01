/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.server.ui;

import java.io.File;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import org.glowroot.common.util.ChunkSource;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class GlowrootLogHttpService implements UnauthenticatedHttpService {

    private final File baseDir;

    GlowrootLogHttpService(File baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public @Nullable FullHttpResponse handleRequest(ChannelHandlerContext ctx, HttpRequest request)
            throws Exception {

        File glowrootLogFile = new File(baseDir, "glowroot.log");
        CharSource glowrootLogCharSource = Files.asCharSource(glowrootLogFile, Charsets.UTF_8);

        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(Names.TRANSFER_ENCODING, Values.CHUNKED);
        response.headers().set(Names.CONTENT_TYPE, "text/plain; charset=UTF-8");
        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        if (keepAlive && !request.getProtocolVersion().isKeepAliveDefault()) {
            response.headers().set(Names.CONNECTION, Values.KEEP_ALIVE);
        }
        HttpServices.preventCaching(response);
        ctx.write(response);
        ChannelFuture future =
                ctx.write(ChunkedInputs.from(ChunkSource.from(glowrootLogCharSource)));
        HttpServices.addErrorListener(future);
        if (!keepAlive) {
            HttpServices.addCloseListener(future);
        }
        // return null to indicate streaming
        return null;
    }
}
