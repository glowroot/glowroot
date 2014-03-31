/*
 * Copyright 2013-2014 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.net.MediaType;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.handler.codec.embedder.EncoderEmbedder;
import org.jboss.netty.handler.codec.http.HttpContentCompressor;
import org.jboss.netty.handler.codec.http.HttpMessage;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ConditionalHttpContentCompressor extends HttpContentCompressor {

    @Override
    @Nullable
    protected EncoderEmbedder<ChannelBuffer> newContentEncoder(HttpMessage msg,
            String acceptEncoding) throws Exception {
        String contentType = msg.headers().get(CONTENT_TYPE);
        if (contentType != null && contentType.equals(MediaType.ZIP.toString())) {
            // don't compress already zipped content
            return null;
        }
        return super.newContentEncoder(msg, acceptEncoding);
    }
}
