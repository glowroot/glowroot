/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.jsp;

class HttpJspPages {

    // this is a (configurable) constant in org.apache.jasper.Constants.JSP_PACKAGE_NAME
    private static final String ORG_APACHE_JSP = "org.apache.jsp";
    private static final String TRAILING_JSP = "_jsp";
    // 002d is hex encoding for illegal class name character '-'
    private static final String WEB_INF = "WEB_002dINF";

    private HttpJspPages() {}

    static String getFilename(Class<?> jspClass) {
        String name = jspClass.getName();
        if (name.startsWith(ORG_APACHE_JSP)) {
            // TODO which application servers does this cover?
            name = name.substring(ORG_APACHE_JSP.length()).replace('.', '/');
            if (name.endsWith(TRAILING_JSP)) {
                name = name.substring(0, name.length() - TRAILING_JSP.length()) + ".jsp";
            }
            int index = name.indexOf(WEB_INF);
            if (index != -1) {
                name = name.substring(0, index) + "WEB-INF"
                        + name.substring(index + WEB_INF.length());
            }
        } else {
            // fallback
            int index = name.lastIndexOf('.');
            if (index != -1) {
                name = name.substring(index + 1);
            }
        }
        return name;
    }
}
