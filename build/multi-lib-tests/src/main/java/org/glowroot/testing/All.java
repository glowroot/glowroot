/**
 * Copyright 2016-2017 the original author or authors.
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
package org.glowroot.testing;

public class All {

    public static void main(String[] args) throws Exception {
        AgentIntegrationTests.main(args);
        Cassandra.main(args);
        Elasticsearch.main(args);
        Executor.main(args);
        Grails.main(args);
        Hibernate.main(args);
        HttpClient.main(args);
        JAXRS.main(args);
        JDBC.main(args);
        JMS.main(args);
        JSF.main(args);
        JSP.main(args);
        Logger.main(args);
        Netty.main(args);
        Play.main(args);
        Quartz.main(args);
        Redis.main(args);
        Servlet.main(args);
        Spring.main(args);
        Struts.main(args);
    }
}
