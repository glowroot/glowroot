/*
 * Copyright 2015-2019 the original author or authors.
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
package org.glowroot.agent.plugin.httpclient._;

import java.lang.reflect.Method;
import java.net.URI;

import org.glowroot.agent.plugin.api.ClassInfo;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.util.Reflection;

public class AsyncHttpClientRequestInvoker {

    private static final Logger logger = Logger.getLogger(AsyncHttpClientRequestInvoker.class);

    private final @Nullable Method getUrlMethod;
    private final @Nullable Method getURIMethod;

    public AsyncHttpClientRequestInvoker(ClassInfo classInfo) {
        Class<?> requestClass = Reflection
                .getClassWithWarnIfNotFound("com.ning.http.client.Request", classInfo.getLoader());
        getUrlMethod = Reflection.getMethod(requestClass, "getUrl");
        // in async-http-client versions from 1.7.12 up until just prior to 1.9.0, getUrl() stripped
        // trailing "/"
        // in these versions only there was method getURI that returned the non-stripped URI
        Method getURIMethod = null;
        if (requestClass != null) {
            try {
                getURIMethod = requestClass.getMethod("getURI");
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
            }
        }
        this.getURIMethod = getURIMethod;
    }

    // TODO report checker framework issue that occurs without this suppression
    @SuppressWarnings("assignment.type.incompatible")
    public String getUrl(Object request) {
        if (getURIMethod == null) {
            return Reflection.invokeWithDefault(getUrlMethod, request, "");
        }
        URI uri = Reflection.invoke(getURIMethod, request);
        return uri == null ? "" : uri.toString();
    }
}
