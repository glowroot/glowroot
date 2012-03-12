/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.configuration;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.xml.parsers.ParserConfigurationException;

import org.informantproject.api.Optional;
import org.informantproject.core.configuration.ImmutableCoreConfiguration.CoreConfigurationBuilder;
import org.informantproject.core.configuration.ImmutablePluginConfiguration.PluginConfigurationBuilder;
import org.informantproject.core.configuration.PluginDescriptor.PropertyDescriptor;
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
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Stateful singleton service for accessing and updating configuration objects. Configuration
 * objects are cached for performance. Also, listeners can be registered with this service in order
 * to receive notifications when configuration objects are updated.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class ConfigurationService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationService.class);

    private final ConfigurationDao configurationDao;

    private final Set<ConfigurationListener> configurationListeners =
            new CopyOnWriteArraySet<ConfigurationListener>();

    private final Map<String, PluginDescriptor> pluginDescriptors;

    private volatile ImmutableCoreConfiguration coreConfiguration;
    private final Map<String, ImmutablePluginConfiguration> pluginConfigurations;

    private final Object updateLock = new Object();

    private final Supplier<List<PluginDescriptor>> packagedPluginDescriptors = Suppliers
            .memoize(new Supplier<List<PluginDescriptor>>() {
                public List<PluginDescriptor> get() {
                    return ImmutableList.copyOf(readPackagedPlugins());
                }
            });

    private final Supplier<List<PluginDescriptor>> installedPluginDescriptors = Suppliers
            .memoize(new Supplier<List<PluginDescriptor>>() {
                public List<PluginDescriptor> get() {
                    return ImmutableList.copyOf(readInstalledPlugins());
                }
            });

    @Inject
    public ConfigurationService(ConfigurationDao configurationDao) {
        logger.debug("<init>");
        this.configurationDao = configurationDao;
        pluginDescriptors = readPluginDescriptors();
        initCoreConfiguration();
        pluginConfigurations = Maps.newHashMap();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors.values()) {
            pluginConfigurations.put(pluginDescriptor.getId(), configurationDao
                    .readPluginConfiguration(pluginDescriptor));
        }
    }

    public ImmutableCoreConfiguration getCoreConfiguration() {
        return coreConfiguration;
    }

    public ImmutablePluginConfiguration getPluginConfiguration(String pluginId) {
        return pluginConfigurations.get(pluginId);
    }

    public void addConfigurationListener(ConfigurationListener listener) {
        configurationListeners.add(listener);
    }

    public void setCoreEnabled(boolean enabled) {
        configurationDao.setCoreEnabled(enabled);
        synchronized (updateLock) {
            CoreConfigurationBuilder builder = new CoreConfigurationBuilder(coreConfiguration);
            builder.setEnabled(enabled);
            coreConfiguration = builder.build();
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    // updates only the supplied properties, in a synchronized block ensuring no clobbering
    public void updateCoreConfiguration(JsonObject propertiesJson) {
        synchronized (updateLock) {
            // copy existing properties
            CoreConfigurationBuilder builder = new CoreConfigurationBuilder(coreConfiguration);
            // overlay updated properties
            builder.setFromJson(propertiesJson);
            coreConfiguration = builder.build();
            configurationDao.storeCoreProperties(coreConfiguration.getPropertiesJson());
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    public void setPluginEnabled(String pluginId, boolean enabled) {
        configurationDao.setPluginEnabled(pluginId, enabled);
        synchronized (updateLock) {
            PluginConfigurationBuilder builder = new PluginConfigurationBuilder(
                    pluginDescriptors.get(pluginId), pluginConfigurations.get(pluginId));
            builder.setEnabled(enabled);
            pluginConfigurations.put(pluginId, builder.build());
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    // updates only the supplied properties, in a synchronized block ensuring no clobbering
    public void storePluginConfiguration(String pluginId, JsonObject propertiesJson) {
        synchronized (updateLock) {
            // start with existing plugin configuration
            PluginConfigurationBuilder builder = new PluginConfigurationBuilder(
                    pluginDescriptors.get(pluginId), pluginConfigurations.get(pluginId));
            // overlay updated properties
            builder.setProperties(propertiesJson);
            ImmutablePluginConfiguration pluginConfiguration = builder.build();
            configurationDao.storePluginProperties(pluginId, pluginConfiguration
                    .getPropertiesJson());
            pluginConfigurations.put(pluginId, pluginConfiguration);
        }
        // it is safe to send the notification to the listeners outside of the update lock because
        // the updated configuration is not passed to the listeners so there shouldn't be a problem
        // even if notifications happen to get sent out of order
        notifyConfigurationListeners();
    }

    public List<PluginDescriptor> getPackagedPluginDescriptors() {
        return packagedPluginDescriptors.get();
    }

    public List<PluginDescriptor> getInstalledPluginDescriptors() {
        return installedPluginDescriptors.get();
    }

    public String getPluginConfigurationJson() {
        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        try {
            jw.beginObject();
            for (PluginDescriptor pluginDescriptor : Iterables.concat(packagedPluginDescriptors
                    .get(), installedPluginDescriptors.get())) {
                jw.name(pluginDescriptor.getId());
                jw.beginObject();
                ImmutablePluginConfiguration pluginConfiguration = pluginConfigurations.get(
                        pluginDescriptor.getId());
                jw.name("enabled");
                jw.value(pluginConfiguration.isEnabled());
                jw.name("properties");
                jw.beginObject();
                writeProperties(pluginDescriptor, pluginConfiguration, jw);
                jw.endObject();
                jw.endObject();
            }
            jw.endObject();
            jw.close();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return sb.toString();
    }

    private void notifyConfigurationListeners() {
        for (ConfigurationListener configurationListener : configurationListeners) {
            configurationListener.onChange();
        }
    }

    private void initCoreConfiguration() {
        // initialize configuration using locally stored values, falling back to defaults if no
        // locally stored values exist
        coreConfiguration = configurationDao.readCoreConfiguration();
        if (coreConfiguration == null) {
            logger.debug("initConfigurations(): default core configuration is being used");
            coreConfiguration = new ImmutableCoreConfiguration();
        } else {
            logger.debug("initConfigurations(): core configuration was read from local data store:"
                    + " {}", coreConfiguration);
        }
    }

    private Map<String, PluginDescriptor> readPluginDescriptors() {
        Map<String, PluginDescriptor> propertyMaps = Maps.newHashMap();
        for (PluginDescriptor pluginDescriptor : Iterables.concat(packagedPluginDescriptors.get(),
                installedPluginDescriptors.get())) {
            propertyMaps.put(pluginDescriptor.getId(), pluginDescriptor);
        }
        return ImmutableMap.copyOf(propertyMaps);
    }

    private void writeProperties(PluginDescriptor pluginDescriptor,
            ImmutablePluginConfiguration pluginConfiguration, JsonWriter jw) throws IOException {

        for (PropertyDescriptor property : pluginDescriptor.getPropertyDescriptors()) {
            if (property.isHidden()) {
                continue;
            }
            jw.name(property.getName());
            if (property.getType().equals("string")) {
                Optional<String> value = pluginConfiguration.getStringProperty(property.getName());
                if (value.isPresent()) {
                    jw.value(value.get());
                } else {
                    jw.nullValue();
                }
            } else if (property.getType().equals("boolean")) {
                jw.value(pluginConfiguration.getBooleanProperty(property.getName()));
            } else if (property.getType().equals("double")) {
                Optional<Double> value = pluginConfiguration.getDoubleProperty(property.getName());
                if (value.isPresent()) {
                    jw.value(value.get());
                } else {
                    jw.nullValue();
                }
            } else {
                logger.error("unexpected type '" + property.getType() + "', this should have"
                        + " been caught by schema validation");
            }
        }
    }

    private Collection<PluginDescriptor> readPackagedPlugins() {
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
            Document document;
            try {
                document = XmlDocuments.getValidatedDocument(resourceURL.openStream());
            } catch (SAXParseException f) {
                logger.error("error reading/validating META-INF/org.informantproject.package.xml: "
                        + f.getMessage(), f);
                return Collections.emptySet();
            }
            Element root = document.getDocumentElement();
            List<PluginDescriptor> plugins = new ArrayList<PluginDescriptor>();
            NodeList pluginsNodes = root.getElementsByTagName("plugins");
            if (pluginsNodes.getLength() > 0) {
                NodeList pluginNodes = ((Element) pluginsNodes.item(0)).getElementsByTagName(
                        "plugin");
                for (int i = 0; i < pluginNodes.getLength(); i++) {
                    Element pluginElement = (Element) pluginNodes.item(i);
                    plugins.add(createPlugin(pluginElement));
                }
            }
            return plugins;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (ParserConfigurationException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private Collection<PluginDescriptor> readInstalledPlugins() {
        try {
            Enumeration<URL> e = PluginJsonService.class.getClassLoader().getResources(
                    "META-INF/org.informantproject.plugin.xml");
            List<PluginDescriptor> plugins = new ArrayList<PluginDescriptor>();
            while (e.hasMoreElements()) {
                URL resourceURL = e.nextElement();
                Document document = XmlDocuments.getValidatedDocument(resourceURL.openStream());
                Element root = document.getDocumentElement();
                plugins.add(createPlugin(root));
            }
            return plugins;
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (ParserConfigurationException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public static PluginDescriptor createPlugin(Element pluginElement) {
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
            NodeList propertyNodes = ((Element) propertiesNodes.item(0))
                    .getElementsByTagName("property");
            for (int i = 0; i < propertyNodes.getLength(); i++) {
                properties.add(createProperty((Element) propertyNodes.item(i)));
            }
        }
        return new PluginDescriptor(name, groupId, artifactId, version, properties);
    }

    private static PropertyDescriptor createProperty(Element propertyElement) {
        String prompt = propertyElement.getElementsByTagName("prompt").item(0).getTextContent();
        String name = propertyElement.getElementsByTagName("name").item(0).getTextContent();
        String type = propertyElement.getElementsByTagName("type").item(0).getTextContent();
        String defaultValueText = getOptionalElementText(propertyElement, "default");
        Object defaultValue = getDefaultValue(type, defaultValueText);
        boolean hidden = Boolean.parseBoolean(getOptionalElementText(propertyElement, "hidden",
                "false"));
        String description = getOptionalElementText(propertyElement, "description");
        return new PropertyDescriptor(prompt, name, type, defaultValue, hidden, description);
    }

    private static Object getDefaultValue(String type, String defaultValueText) {
        if (defaultValueText == null) {
            return null;
        }
        if (type.equals("string")) {
            return defaultValueText;
        } else if (type.equals("boolean")) {
            if (defaultValueText.equalsIgnoreCase("true")) {
                return true;
            } else if (defaultValueText.equalsIgnoreCase("false")) {
                return false;
            } else {
                logger.error("unexpected boolean value '" + defaultValueText
                        + "', must be either 'true' or 'false'");
                return null;
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

    private static String getOptionalElementText(Element element, String tagName) {
        return getOptionalElementText(element, tagName, null);
    }

    private static String getOptionalElementText(Element element, String tagName,
            String defaultValue) {

        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return defaultValue;
        } else {
            return nodes.item(0).getTextContent();
        }
    }

    public interface ConfigurationListener {
        // the new configuration is not passed to onChange so that the receiver has to get the
        // latest which avoids race condition worries that two updates may get sent to the receiver
        // in the wrong order
        void onChange();
    }
}
