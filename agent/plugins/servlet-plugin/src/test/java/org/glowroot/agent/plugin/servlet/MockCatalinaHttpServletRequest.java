/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.mock.web.MockHttpServletRequest;

// this is a mock request object that mimics tomcat's getParameterMap() behavior
//
// this tests that the servlet-plugin doesn't try to capture the request parameters (which it does
// via getParameterMap()) after the call to getParameterValues() (if getParameterValues() is called
// from inside of getParameterMap()), since this would lock the parameter map causing the outer call
// to getParameterMap() to fail
public class MockCatalinaHttpServletRequest extends MockHttpServletRequest {

    private final ParameterMap<String, String[]> parameterMap =
            new ParameterMap<String, String[]>();

    public MockCatalinaHttpServletRequest(String method, String requestURI) {
        super(method, requestURI);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (parameterMap.isLocked()) {
            return parameterMap;
        }
        Enumeration<String> enumeration = getParameterNames();
        while (enumeration.hasMoreElements()) {
            String name = enumeration.nextElement().toString();
            String[] values = getParameterValues(name);
            parameterMap.put(name, values);
        }
        parameterMap.setLocked(true);
        return parameterMap;
    }

    @SuppressWarnings("serial")
    private class ParameterMap<K, V> extends HashMap<K, V> {
        private boolean locked = false;

        @Override
        public V put(K key, V value) {
            if (locked) {
                throw new IllegalStateException(
                        "Cannot modify locked ParameterMap, check isLocked() first");
            }
            return super.put(key, value);
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }
    }
}
