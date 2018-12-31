/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.netty;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;

import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

// copied from Netty in order to test against old 4.1.x versions since this class does not exist
// prior to 4.1.10.Final
public class CleartextHttp2ServerUpgradeHandler extends ChannelHandlerAdapter {

    private static final ByteBuf CONNECTION_PREFACE = unreleasableBuffer(connectionPrefaceBuf());

    private final HttpServerCodec httpServerCodec;
    private final HttpServerUpgradeHandler httpServerUpgradeHandler;
    private final ChannelHandler http2ServerHandler;

    public CleartextHttp2ServerUpgradeHandler(HttpServerCodec httpServerCodec,
            HttpServerUpgradeHandler httpServerUpgradeHandler,
            ChannelHandler http2ServerHandler) {
        this.httpServerCodec = checkNotNull(httpServerCodec, "httpServerCodec");
        this.httpServerUpgradeHandler =
                checkNotNull(httpServerUpgradeHandler, "httpServerUpgradeHandler");
        this.http2ServerHandler = checkNotNull(http2ServerHandler, "http2ServerHandler");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline()
                .addBefore(ctx.name(), null, new PriorKnowledgeHandler())
                .addBefore(ctx.name(), null, httpServerCodec)
                .replace(this, null, httpServerUpgradeHandler);
    }

    private final class PriorKnowledgeHandler extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
                throws Exception {
            int prefaceLength = CONNECTION_PREFACE.readableBytes();
            int bytesRead = Math.min(in.readableBytes(), prefaceLength);

            if (!ByteBufUtil.equals(CONNECTION_PREFACE, CONNECTION_PREFACE.readerIndex(),
                    in, in.readerIndex(), bytesRead)) {
                ctx.pipeline().remove(this);
            } else if (bytesRead == prefaceLength) {
                // Full h2 preface match, removed source codec, using http2 codec to handle
                // following network traffic
                ctx.pipeline()
                        .remove(httpServerCodec)
                        .remove(httpServerUpgradeHandler);

                ctx.pipeline().addAfter(ctx.name(), null, http2ServerHandler);
                ctx.pipeline().remove(this);

                ctx.fireUserEventTriggered(PriorKnowledgeUpgradeEvent.INSTANCE);
            }
        }
    }

    public static final class PriorKnowledgeUpgradeEvent {

        private static final PriorKnowledgeUpgradeEvent INSTANCE = new PriorKnowledgeUpgradeEvent();

        private PriorKnowledgeUpgradeEvent() {}
    }
}
