/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.plugin.servlet;

import java.util.Enumeration;

import org.informantproject.api.UnresolvedMethod;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class HttpSession {

    private static final UnresolvedMethod getIdMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpSession", "getId");
    private static final UnresolvedMethod isNewMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpSession", "isNew");
    private static final UnresolvedMethod getAttributeNamesMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpSession", "getAttributeNames");
    private static final UnresolvedMethod getAttributeMethod = new UnresolvedMethod(
            "javax.servlet.http.HttpSession", "getAttribute", String.class);

    private final Object realSession;

    private HttpSession(Object realSession) {
        this.realSession = realSession;
    }

    String getId() {
        return (String) getIdMethod.invoke(realSession);
    }

    boolean isNew() {
        return (Boolean) isNewMethod.invoke(realSession);
    }

    Enumeration<?> getAttributeNames() {
        return (Enumeration<?>) getAttributeNamesMethod.invoke(realSession);
    }

    Object getAttribute(String name) {
        return getAttributeMethod.invoke(realSession, name);
    }

    static HttpSession from(Object realSession) {
        return realSession == null ? null : new HttpSession(realSession);
    }
}
