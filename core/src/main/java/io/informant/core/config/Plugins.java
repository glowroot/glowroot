/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.core.config;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.config.PluginDescriptor.PropertyDescriptor;
import io.informant.core.util.Resources2;
import io.informant.core.util.Static;
import io.informant.core.util.XmlDocuments;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public final class Plugins {

    private static final Logger logger = LoggerFactory.getLogger(Plugins.class);

    private static final Supplier<ImmutableList<PluginDescriptor>> pluginDescriptors =
            Suppliers.memoize(new Supplier<ImmutableList<PluginDescriptor>>() {
                public ImmutableList<PluginDescriptor> get() {
                    return ImmutableList.copyOf(Iterables.concat(readPackagedPlugins(),
                            readInstalledPlugins()));
                }
            });

    // don't return ImmutableList since this method is used by UiTestingMain and when UiTestingMain
    // is compiled by maven, it is compiled against shaded informant, but then if it is run inside
    // an IDE without rebuilding UiTestingMain it will fail since informant is then unshaded
    @ReadOnly
    public static List<PluginDescriptor> getPluginDescriptors() {
        return pluginDescriptors.get();
    }

    @ReadOnly
    private static List<PluginDescriptor> readInstalledPlugins() {
        try {
            List<PluginDescriptor> plugins = Lists.newArrayList();
            for (URL url : Resources2.getResources("META-INF/io.informant.plugin.xml")) {
                Document document = XmlDocuments.newValidatedDocument(Resources
                        .newInputStreamSupplier(url));
                Element root = document.getDocumentElement();
                plugins.add(createPluginDescriptor(root));
            }
            return plugins;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        } catch (ParserConfigurationException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    @ReadOnly
    private static List<PluginDescriptor> readPackagedPlugins() {
        try {
            List<URL> urls = Resources2.getResources("META-INF/io.informant.package.xml");
            if (urls.isEmpty()) {
                return ImmutableList.of();
            }
            if (urls.size() > 1) {
                List<String> resourcePaths = Lists.newArrayList();
                for (URL url : urls) {
                    resourcePaths.add("'" + url.getPath() + "'");
                }
                logger.error("more than one resource found with name 'META-INF"
                        + "/io.informant.package.xml'. This file is only supported inside of an"
                        + " informant packaged jar so there should be only one. Only using the"
                        + " first one of " + Joiner.on(", ").join(resourcePaths) + ".");
            }
            Document document;
            try {
                document = XmlDocuments.newValidatedDocument(Resources
                        .newInputStreamSupplier(urls.get(0)));
            } catch (SAXParseException f) {
                logger.error("error reading/validating META-INF/io.informant.package.xml: "
                        + f.getMessage(), f);
                return ImmutableList.of();
            }
            Element root = document.getDocumentElement();
            List<PluginDescriptor> plugins = Lists.newArrayList();
            NodeList pluginsNodes = root.getElementsByTagName("plugins");
            if (pluginsNodes.getLength() > 0) {
                NodeList pluginNodes = ((Element) pluginsNodes.item(0))
                        .getElementsByTagName("plugin");
                for (int i = 0; i < pluginNodes.getLength(); i++) {
                    Element pluginElement = (Element) pluginNodes.item(i);
                    plugins.add(createPluginDescriptor(pluginElement));
                }
            }
            return plugins;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        } catch (ParserConfigurationException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    private static PluginDescriptor createPluginDescriptor(Element pluginElement) {
        String name = pluginElement.getElementsByTagName("name").item(0).getTextContent();
        String groupId = pluginElement.getElementsByTagName("groupId").item(0).getTextContent();
        String artifactId = pluginElement.getElementsByTagName("artifactId").item(0)
                .getTextContent();
        String version = pluginElement.getElementsByTagName("version").item(0).getTextContent();
        NodeList propertiesNodes = pluginElement.getElementsByTagName("properties");
        List<PropertyDescriptor> properties = Lists.newArrayList();
        if (propertiesNodes.getLength() > 0) {
            NodeList propertyNodes = ((Element) propertiesNodes.item(0))
                    .getElementsByTagName("property");
            for (int i = 0; i < propertyNodes.getLength(); i++) {
                properties.add(createPropertyDescriptor((Element) propertyNodes.item(i)));
            }
        }
        List<String> aspects = Lists.newArrayList();
        NodeList aspectsNodes = pluginElement.getElementsByTagName("aspects");
        NodeList aspectNodes = ((Element) aspectsNodes.item(0)).getElementsByTagName("aspect");
        for (int i = 0; i < aspectNodes.getLength(); i++) {
            aspects.add(aspectNodes.item(i).getTextContent());
        }
        return new PluginDescriptor(name, groupId, artifactId, version, properties, aspects);
    }

    private static PropertyDescriptor createPropertyDescriptor(Element propertyElement) {
        String prompt = propertyElement.getElementsByTagName("prompt").item(0).getTextContent();
        String name = propertyElement.getElementsByTagName("name").item(0).getTextContent();
        String type = propertyElement.getElementsByTagName("type").item(0).getTextContent();
        String defaultValueText = getOptionalElementText(propertyElement, "default");
        Object defaultValue;
        if (defaultValueText != null) {
            defaultValue = getDefaultValue(type, defaultValueText);
        } else if (type.equals("boolean")) {
            defaultValue = false;
        } else if (type.equals("string")) {
            defaultValue = "";
        } else {
            defaultValue = null;
        }
        boolean hidden = Boolean.parseBoolean(getOptionalElementText(propertyElement, "hidden"));
        String description = getOptionalElementText(propertyElement, "description");
        return new PropertyDescriptor(prompt, name, type, defaultValue, hidden, description);
    }

    @Nullable
    private static Object getDefaultValue(String type, String defaultValueText) {
        if (type.equals("string")) {
            return defaultValueText;
        } else if (type.equals("boolean")) {
            if (defaultValueText.equalsIgnoreCase("true")) {
                return true;
            } else if (defaultValueText.equalsIgnoreCase("false")) {
                return false;
            } else {
                logger.error("unexpected boolean value '" + defaultValueText + "', must be either"
                        + " 'true' or 'false', defaulting to 'false'");
                return false;
            }
        } else if (type.equals("double")) {
            try {
                return Double.parseDouble(defaultValueText);
            } catch (NumberFormatException e) {
                logger.error("unable to parse default value '" + defaultValueText
                        + "' as a double");
                return null;
            }
        } else {
            logger.error("unexpected type '" + type + "', this should have"
                    + " been caught by schema validation");
            return null;
        }
    }

    @Nullable
    private static String getOptionalElementText(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        } else {
            return nodes.item(0).getTextContent();
        }
    }

    private Plugins() {}
}
