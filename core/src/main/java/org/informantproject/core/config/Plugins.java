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
package org.informantproject.core.config;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.xml.parsers.ParserConfigurationException;

import org.informantproject.core.config.PluginDescriptor.PropertyDescriptor;
import org.informantproject.core.util.XmlDocuments;
import org.informantproject.local.ui.PluginJsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Plugins {

    private static final Logger logger = LoggerFactory.getLogger(Plugins.class);

    private static final Supplier<List<PluginDescriptor>> packagedPluginDescriptors = Suppliers
            .memoize(new Supplier<List<PluginDescriptor>>() {
                public List<PluginDescriptor> get() {
                    return ImmutableList.copyOf(readPackagedPlugins());
                }
            });

    private static final Supplier<List<PluginDescriptor>> installedPluginDescriptors = Suppliers
            .memoize(new Supplier<List<PluginDescriptor>>() {
                public List<PluginDescriptor> get() {
                    return ImmutableList.copyOf(readInstalledPlugins());
                }
            });

    private static final Supplier<Map<String, PluginDescriptor>> pluginDescriptorMap = Suppliers
            .memoize(new Supplier<Map<String, PluginDescriptor>>() {
                public Map<String, PluginDescriptor> get() {
                    return ImmutableMap.copyOf(buildPluginDescriptorMap());
                }
            });

    private Plugins() {}

    public static List<PluginDescriptor> getPackagedPluginDescriptors() {
        return packagedPluginDescriptors.get();
    }

    public static List<PluginDescriptor> getInstalledPluginDescriptors() {
        return installedPluginDescriptors.get();
    }

    public static PluginDescriptor getDescriptor(String pluginId) {
        return pluginDescriptorMap.get().get(pluginId);
    }

    private static Collection<PluginDescriptor> readInstalledPlugins() {
        try {
            Enumeration<URL> e = PluginJsonService.class.getClassLoader().getResources(
                    "META-INF/org.informantproject.plugin.xml");
            List<PluginDescriptor> plugins = Lists.newArrayList();
            while (e.hasMoreElements()) {
                URL resourceURL = e.nextElement();
                Document document = XmlDocuments.newValidatedDocument(Resources
                        .newInputStreamSupplier(resourceURL));
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

    private static Collection<PluginDescriptor> readPackagedPlugins() {
        try {
            Enumeration<URL> e = PluginJsonService.class.getClassLoader().getResources(
                    "META-INF/org.informantproject.package.xml");
            if (!e.hasMoreElements()) {
                return ImmutableList.of();
            }
            URL resourceURL = e.nextElement();
            if (e.hasMoreElements()) {
                List<String> resourcePaths = Lists.newArrayList();
                resourcePaths.add("'" + resourceURL.getPath() + "'");
                while (e.hasMoreElements()) {
                    resourcePaths.add("'" + e.nextElement().getPath() + "'");
                }
                logger.error("More than one resource found with name 'META-INF"
                        + "/org.informantproject.package.xml'. This file is only supported inside"
                        + " of an informant packaged jar so there should be only one. Only using"
                        + " the first one of " + Joiner.on(", ").join(resourcePaths) + ".");
            }
            Document document;
            try {
                document = XmlDocuments.newValidatedDocument(Resources.newInputStreamSupplier(
                        resourceURL));
            } catch (SAXParseException f) {
                logger.error("error reading/validating META-INF/org.informantproject.package.xml: "
                        + f.getMessage(), f);
                return ImmutableSet.of();
            }
            Element root = document.getDocumentElement();
            List<PluginDescriptor> plugins = Lists.newArrayList();
            NodeList pluginsNodes = root.getElementsByTagName("plugins");
            if (pluginsNodes.getLength() > 0) {
                NodeList pluginNodes = ((Element) pluginsNodes.item(0)).getElementsByTagName(
                        "plugin");
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

    private static Map<String, PluginDescriptor> buildPluginDescriptorMap() {
        Map<String, PluginDescriptor> descriptorMap = Maps.newHashMap();
        for (PluginDescriptor pluginDescriptor : Plugins.getPackagedPluginDescriptors()) {
            descriptorMap.put(pluginDescriptor.getId(), pluginDescriptor);
        }
        for (PluginDescriptor pluginDescriptor : Plugins
                .getInstalledPluginDescriptors()) {
            descriptorMap.put(pluginDescriptor.getId(), pluginDescriptor);
        }
        return descriptorMap;
    }

    private static PluginDescriptor createPluginDescriptor(Element pluginElement) {
        String name = pluginElement.getElementsByTagName("name").item(0).getTextContent();
        String groupId = pluginElement.getElementsByTagName("groupId").item(0)
                .getTextContent();
        String artifactId = pluginElement.getElementsByTagName("artifactId").item(0)
                .getTextContent();
        String version = pluginElement.getElementsByTagName("version").item(0)
                .getTextContent();
        NodeList propertiesNodes = pluginElement.getElementsByTagName("properties");
        List<PropertyDescriptor> properties = Lists.newArrayList();
        if (propertiesNodes.getLength() > 0) {
            NodeList propertyNodes = ((Element) propertiesNodes.item(0)).getElementsByTagName(
                    "property");
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
}
