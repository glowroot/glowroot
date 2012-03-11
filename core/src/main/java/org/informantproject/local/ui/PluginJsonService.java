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
package org.informantproject.local.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;

/**
 * Json service to read installed and installable plugins. Bound to url "/plugin" in HttpServer.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class PluginJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(PluginJsonService.class);

    private static final String INSTALLABLE_PLUGINS_URL = "oss.sonatype.org/content/repositories"
            + "/snapshots/org/informantproject/plugins/";

    private final AsyncHttpClient asyncHttpClient;

    private final Supplier<Collection<Plugin>> packagedPlugins = Suppliers
            .memoize(new Supplier<Collection<Plugin>>() {
                public Collection<Plugin> get() {
                    return readPackagedPlugins();
                }
            });

    private final Supplier<Collection<Plugin>> installedPlugins = Suppliers
            .memoize(new Supplier<Collection<Plugin>>() {
                public Collection<Plugin> get() {
                    return readInstalledPlugins();
                }
            });

    // expire the list of installable plugins every hour in order to pick up new installable plugins
    private final Supplier<Collection<Plugin>> installablePlugins = Suppliers
            .memoizeWithExpiration(new Supplier<Collection<Plugin>>() {
                public Collection<Plugin> get() {
                    return readInstallablePlugins();
                }
            }, 3600, TimeUnit.SECONDS);

    @Inject
    public PluginJsonService(AsyncHttpClient asyncHttpClient) {
        this.asyncHttpClient = asyncHttpClient;
    }

    // called dynamically from HttpServer
    public String getPackagedPlugins() {
        return new Gson().toJson(packagedPlugins.get());
    }

    // called dynamically from HttpServer
    public String getInstalledPlugins() {
        return new Gson().toJson(installedPlugins.get());
    }

    // called dynamically from HttpServer
    public String getInstallablePlugins() {
        List<Plugin> notAlreadyInstalled = Lists.newArrayList(installablePlugins.get());
        // this works because Plugin.equals() is defined only in terms of groupId and artifactId
        Iterables.removeAll(notAlreadyInstalled, packagedPlugins.get());
        Iterables.removeAll(notAlreadyInstalled, installedPlugins.get());
        return new Gson().toJson(notAlreadyInstalled);
    }

    // informant and a set of plugins can be packaged together in a single jar in order to simplify
    // distribution and installation. any plugins packaged with informant cannot be uninstalled
    private Collection<Plugin> readPackagedPlugins() {
        try {
            Enumeration<URL> e = PluginJsonService.class.getClassLoader().getResources(
                    "META-INF/org.informantproject.package.xml");
            if (!e.hasMoreElements()) {
                return Collections.emptyList();
            }
            URL resourceURL = e.nextElement();
            if (e.hasMoreElements()) {
                List<String> resourcePaths = new ArrayList<String>();
                resourcePaths.add("'" + resourceURL.getPath() + "'");
                while (e.hasMoreElements()) {
                    resourcePaths.add("'" + e.nextElement().getPath() + "'");
                }
                logger.error("More than one resource found with name 'META-INF"
                        + "/org.informantproject.package.xml'. This file is only supported inside"
                        + " of an informant packaged jar so there should be only one. Only using"
                        + " the first one of " + Joiner.on(", ").join(resourcePaths) + ".");
            }
            Document doc = getValidatedDocument(resourceURL.openStream(),
                    "org/informantproject/local/schema/plugins-1.0.xsd");
            Element root = doc.getDocumentElement();
            NodeList pluginNodeList = root.getElementsByTagName("plugin");
            List<Plugin> plugins = new ArrayList<Plugin>();
            for (int i = 0; i < pluginNodeList.getLength(); i++) {
                Element pluginElement = (Element) pluginNodeList.item(i);
                String name = pluginElement.getElementsByTagName("name").item(0).getTextContent();
                String groupId = pluginElement.getElementsByTagName("groupId").item(0)
                        .getTextContent();
                String artifactId = pluginElement.getElementsByTagName("artifactId").item(0)
                        .getTextContent();
                String version = pluginElement.getElementsByTagName("version").item(0)
                        .getTextContent();
                plugins.add(new Plugin(name, groupId, artifactId, version));
            }
            return plugins;
        } catch (ParserConfigurationException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private Collection<Plugin> readInstalledPlugins() {
        try {
            Enumeration<URL> e = PluginJsonService.class.getClassLoader().getResources(
                    "META-INF/org.informantproject.plugin.properties");
            List<Plugin> plugins = new ArrayList<Plugin>();
            while (e.hasMoreElements()) {
                URL url = e.nextElement();
                Properties pluginProperties = new Properties();
                InputStream pluginPropetiesIn = url.openStream();
                try {
                    pluginProperties.load(pluginPropetiesIn);
                } finally {
                    pluginPropetiesIn.close();
                }
                String name = pluginProperties.getProperty("name");
                String groupId = pluginProperties.getProperty("groupId");
                String artifactId = pluginProperties.getProperty("artifactId");
                String version = pluginProperties.getProperty("version");
                plugins.add(new Plugin(name, groupId, artifactId, version));
            }
            return plugins;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private Collection<Plugin> readInstallablePlugins() {
        // TODO configure AsyncHttpClient to handle HTTPS
        BoundRequestBuilder request = asyncHttpClient.prepareGet("http://"
                + INSTALLABLE_PLUGINS_URL);
        String body;
        try {
            body = request.execute().get().getResponseBody();
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (ExecutionException e) {
            logger.error(e.getMessage(), e.getCause());
            return Collections.emptyList();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
        Pattern pattern = Pattern.compile(INSTALLABLE_PLUGINS_URL + "([^/]+)/");
        Matcher matcher = pattern.matcher(body);
        List<Plugin> installablePlugins = new ArrayList<Plugin>();
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            try {
                String metadataUrl = "http://" + INSTALLABLE_PLUGINS_URL + artifactId
                        + "/maven-metadata.xml";
                String metadata = asyncHttpClient.prepareGet(metadataUrl).execute().get()
                        .getResponseBody();
                String version = getVersionFromMetadata(metadata);
                installablePlugins.add(new Plugin(artifactId, "org.informantproject.plugins",
                        artifactId, version));
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            } catch (ExecutionException e) {
                logger.error(e.getMessage(), e.getCause());
            } catch (ParserConfigurationException e) {
                logger.error(e.getMessage(), e);
            } catch (SAXException e) {
                logger.error(e.getMessage(), e);
            }
        }
        return Collections2.filter(installablePlugins, new Predicate<Plugin>() {
            public boolean apply(Plugin plugin) {
                for (Plugin installedPlugin : installedPlugins.get()) {
                    if (plugin.groupId.equals(installedPlugin.groupId)
                            && plugin.artifactId.equals(installedPlugin.artifactId)) {
                        return false;
                    }
                }
                return true;
            }
        });
    }

    private static String getVersionFromMetadata(String metadata)
            throws ParserConfigurationException, SAXException, IOException {

        byte[] xmlBytes = metadata.getBytes(Charsets.UTF_8.name());
        Element root = getNonValidatedDocument(new ByteArrayInputStream(xmlBytes))
                .getDocumentElement();
        return root.getElementsByTagName("version").item(0).getTextContent();
    }

    private static Document getNonValidatedDocument(InputStream inputStream)
            throws ParserConfigurationException, SAXException, IOException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(inputStream);
    }

    private static Document getValidatedDocument(InputStream inputStream, String schemaResourcePath)
            throws ParserConfigurationException, SAXException, IOException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        InputStream resourceIn = PluginJsonService.class.getClassLoader().getResourceAsStream(
                schemaResourcePath);
        try {
            factory.setSchema(schemaFactory
                    .newSchema(new Source[] { new StreamSource(resourceIn) }));
        } finally {
            resourceIn.close();
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(inputStream);
    }

    private static class Plugin {
        // fields are read via reflection by Gson
        @SuppressWarnings("unused")
        private final String name;
        private final String groupId;
        private final String artifactId;
        @SuppressWarnings("unused")
        private final String version;
        public Plugin(String name, String groupId, String artifactId, String version) {
            this.name = name;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }
        // equality is defined only in terms of groupId and artifactId
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Plugin)) {
                return false;
            }
            Plugin other = (Plugin) o;
            return groupId.equals(other.groupId) && artifactId.equals(other.artifactId);
        }
        @Override
        public int hashCode() {
            return Objects.hashCode(groupId, artifactId);
        }
    }
}
