/*
 * Copyright 2013-2017 the original author or authors.
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
import java.nio.channels.ClosedChannelException;

import com.google.common.collect.ImmutableSet;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HttpServices {

    private static final Logger logger = LoggerFactory.getLogger(HttpServices.class);

    private static final ImmutableSet<String> BROWSER_DISCONNECT_MESSAGES =
            ImmutableSet.of("An existing connection was forcibly closed by the remote host",
                    "An established connection was aborted by the software in your host machine",
                    "Connection reset by peer");

    private HttpServices() {}

    @SuppressWarnings("argument.type.incompatible")
    static void addErrorListener(ChannelFuture future) {
        future.addListener(new GenericFutureListener<ChannelFuture>() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                Throwable cause = future.cause();
                if (cause == null) {
                    return;
                }
                if (shouldLogException(cause)) {
                    logger.error(cause.getMessage(), cause);
                }
                future.channel().close();
            }
        });
    }

    @SuppressWarnings("argument.type.incompatible")
    static void addCloseListener(ChannelFuture future) {
        future.addListener(ChannelFutureListener.CLOSE);
    }

    static boolean shouldLogException(Throwable t) {
        if (t instanceof InterruptedException) {
            // ignore, probably just termination
            logger.debug(t.getMessage(), t);
            return false;
        }
        if (t instanceof IOException && BROWSER_DISCONNECT_MESSAGES.contains(t.getMessage())) {
            // ignore, just a browser disconnect
            logger.debug(t.getMessage(), t);
            return false;
        }
        if (t instanceof ClosedChannelException) {
            // ignore, just a browser disconnect
            logger.debug(t.getMessage(), t);
            return false;
        }
        return true;
    }
}
