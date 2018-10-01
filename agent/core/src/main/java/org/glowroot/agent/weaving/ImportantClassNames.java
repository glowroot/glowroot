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
package org.glowroot.agent.weaving;

// LIMIT DEPENDENCY USAGE IN THIS CLASS SO IT DOESN'T TRIGGER ANY CLASS LOADING ON ITS OWN
public class ImportantClassNames {

    public static final String JBOSS_WELD_HACK_CLASS_NAME = "org/jboss/weld/util/Decorators";
    public static final String JBOSS_MODULES_HACK_CLASS_NAME = "org/jboss/modules/Module";
    public static final String JBOSS_URL_HACK_CLASS_NAME =
            "org/jboss/net/protocol/URLStreamHandlerFactory";
    public static final String FELIX_OSGI_HACK_CLASS_NAME =
            "org/apache/felix/framework/BundleWiringImpl";
    public static final String FELIX3_OSGI_HACK_CLASS_NAME =
            "org/apache/felix/framework/ModuleImpl";
    public static final String ECLIPSE_OSGI_HACK_CLASS_NAME =
            "org/eclipse/osgi/internal/framework/EquinoxContainer";
    public static final String OPENEJB_HACK_CLASS_NAME =
            "org/apache/openejb/util/classloader/URLClassLoaderFirst";
    // this is needed for HikariCP prior to 2.3.10, specifically, prior to
    // https://github.com/brettwooldridge/HikariCP/commit/33a4bbe54a99de14a704c1a26e3f953cfa7888ad
    public static final String HIKARI_CP_PROXY_HACK_CLASS_NAME =
            "com/zaxxer/hikari/proxy/JavassistProxyFactory";

    private ImportantClassNames() {}
}
