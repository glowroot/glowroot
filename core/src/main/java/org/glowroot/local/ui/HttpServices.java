/*
 * Copyright 2013-2015 the original author or authors.
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

import java.util.Date;

import com.google.common.base.Charsets;
import com.google.common.net.MediaType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class HttpServices {

    private HttpServices() {}

    static void preventCaching(HttpResponse response) {
        // prevent caching of dynamic json data, using 'definitive' minimum set of headers from
        // http://stackoverflow.com/questions/49547/
        // making-sure-a-web-page-is-not-cached-across-all-browsers/2068407#2068407
        response.headers().set(Names.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.headers().set(Names.PRAGMA, "no-cache");
        response.headers().set(Names.EXPIRES, new Date(0));
    }

    static FullHttpResponse createJsonResponse(String content, HttpResponseStatus status) {
        ByteBuf byteBuf = Unpooled.copiedBuffer(content, Charsets.ISO_8859_1);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, byteBuf);
        response.headers().add(Names.CONTENT_TYPE, MediaType.JSON_UTF_8);
        HttpServices.preventCaching(response);
        return response;
    }
}
