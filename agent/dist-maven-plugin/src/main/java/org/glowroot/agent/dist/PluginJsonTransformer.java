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
package org.glowroot.agent.dist;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import org.glowroot.agent.config.ImmutablePluginDescriptor;
import org.glowroot.agent.config.ImmutablePropertyDescriptor;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.config.PropertyDescriptor;
import org.glowroot.agent.config.PropertyValue;
import org.glowroot.agent.dist.PluginConfig.PropertyConfig;

class PluginJsonTransformer {

    private final MavenProject project;
    private final PluginConfig[] pluginConfigs;

    PluginJsonTransformer(MavenProject project, PluginConfig[] pluginConfigs) {
        this.project = project;
        this.pluginConfigs = pluginConfigs;
    }

    void execute() throws Exception {
        Set<Artifact> artifacts = project.getDependencyArtifacts();
        createArtifactJar(artifacts);
    }

    private void createArtifactJar(Set<Artifact> artifacts) throws Exception {
        List<PluginDescriptor> pluginDescriptors = getPluginDescriptors(artifacts);
        validateConfigForDuplicates();
        for (PluginConfig pluginConfig : pluginConfigs) {
            validateConfigItem(pluginDescriptors, pluginConfig);
        }
        String pluginsJson = transform(pluginDescriptors);
        File metaInfDir = new File(project.getBuild().getOutputDirectory(), "META-INF");
        File file = new File(metaInfDir, "glowroot.plugins.json");
        if (!metaInfDir.exists() && !metaInfDir.mkdirs()) {
            throw new IOException("Could not create directory: " + metaInfDir.getAbsolutePath());
        }
        Files.write(pluginsJson, file, Charsets.UTF_8);
    }

    private static List<PluginDescriptor> getPluginDescriptors(Set<Artifact> artifacts)
            throws IOException {
        List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
        for (Artifact artifact : artifacts) {
            String content = getGlowrootPluginJson(artifact);
            if (content == null) {
                continue;
            }
            // de-serialization of shaded immutables objects needs to be done using shaded jackson
            pluginDescriptors.add(PluginDescriptor.readValue(content));
        }
        return pluginDescriptors;
    }

    private static @Nullable String getGlowrootPluginJson(Artifact artifact) throws IOException {
        File artifactFile = artifact.getFile();
        if (artifactFile.isDirectory()) {
            File jsonFile = new File(artifactFile, "META-INF/glowroot.plugin.json");
            if (!jsonFile.exists()) {
                return null;
            }
            FileReader reader = new FileReader(jsonFile);
            try {
                return CharStreams.toString(reader);
            } finally {
                reader.close();
            }
        }
        JarInputStream jarIn = new JarInputStream(new FileInputStream(artifact.getFile()));
        try {
            JarEntry jarEntry;
            while ((jarEntry = jarIn.getNextJarEntry()) != null) {
                String name = jarEntry.getName();
                if (jarEntry.isDirectory()) {
                    continue;
                }
                if (!name.equals("META-INF/glowroot.plugin.json")) {
                    continue;
                }
                InputStreamReader in = new InputStreamReader(jarIn, Charsets.UTF_8);
                String content = CharStreams.toString(in);
                in.close();
                return content;
            }
            return null;
        } finally {
            jarIn.close();
        }
    }

    private void validateConfigForDuplicates() throws MojoExecutionException {
        for (PluginConfig pluginConfig : pluginConfigs) {
            for (PluginConfig pluginConfig2 : pluginConfigs) {
                if (pluginConfig != pluginConfig2
                        && Objects.equal(pluginConfig.getId(), pluginConfig2.getId())) {
                    throw new MojoExecutionException("Found duplicate <plugin> tags"
                            + " (same groupId and artifactId) under <configuration>");
                }
            }
        }
    }

    private void validateConfigItem(List<PluginDescriptor> pluginDescriptors,
            PluginConfig pluginConfig) throws MojoExecutionException {
        PluginDescriptor pluginDescriptor = getPlugin(pluginConfig, pluginDescriptors);
        if (pluginDescriptor == null) {
            throw new MojoExecutionException("Found <plugin> tag under <configuration> that"
                    + " doesn't have a corresponding dependency in the pom file");
        }
        // check for property names with missing corresponding property name in property descriptor
        validateProperties(pluginConfig, pluginDescriptor);
    }

    private @Nullable PluginDescriptor getPlugin(PluginConfig pluginConfig,
            List<PluginDescriptor> pluginDescriptors) {
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            if (pluginDescriptor.id().equals(pluginConfig.getId())) {
                return pluginDescriptor;
            }
        }
        return null;
    }

    private void validateProperties(PluginConfig pluginConfig, PluginDescriptor pluginDescriptor)
            throws MojoExecutionException {
        for (PropertyConfig propertyConfig : pluginConfig.getProperties()) {
            String propertyName = propertyConfig.getName();
            if (propertyName == null || propertyName.length() == 0) {
                throw new MojoExecutionException("Missing or empty <name> under"
                        + " <configuration>/<plugins>/<plugin>/<properties>/<property>");
            }
            boolean found = false;
            for (PropertyDescriptor propertyDescriptor : pluginDescriptor.properties()) {
                if (propertyDescriptor.name().equals(propertyName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new MojoExecutionException("Found <property> tag with name '" + propertyName
                        + "' under <configuration>/<plugins>/<plugin>/<properties> that doesn't"
                        + " have a corresponding property defined in the plugin '"
                        + pluginDescriptor.id() + "'");
            }
        }
    }

    private String transform(List<PluginDescriptor> pluginDescriptors) throws Exception {
        List<PluginDescriptor> updatedPlugins = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            ImmutablePluginDescriptor updatedPlugin =
                    ImmutablePluginDescriptor.copyOf(pluginDescriptor)
                            .withProperties(getPropertiesWithOverrides(pluginDescriptor));
            updatedPlugins.add(updatedPlugin);
        }
        // serialization of shaded immutables objects needs to be done using shaded jackson
        return PluginDescriptor.writeValue(updatedPlugins);
    }

    private @Nullable PluginConfig getPluginConfig(String id) {
        for (PluginConfig pluginConfig : pluginConfigs) {
            if (id.equals(pluginConfig.getId())) {
                return pluginConfig;
            }
        }
        return null;
    }

    private List<PropertyDescriptor> getPropertiesWithOverrides(PluginDescriptor pluginDescriptor)
            throws MojoExecutionException {
        List<PropertyDescriptor> properties = Lists.newArrayList();
        PluginConfig pluginConfig = getPluginConfig(pluginDescriptor.id());
        for (PropertyDescriptor property : pluginDescriptor.properties()) {
            PropertyConfig override = getPropertyConfig(pluginConfig, property.name());
            if (override == null) {
                properties.add(property);
                continue;
            }

            PropertyDescriptorOverlay overlay = new PropertyDescriptorOverlay(property);
            String overrideDefault = override.getDefault();
            String overrideDescription = override.getDescription();
            if (overrideDefault != null) {
                overlay.setDefault(getDefaultFromText(overrideDefault, property.type()));
            }
            if (overrideDescription != null) {
                overlay.setDescription(overrideDescription);
            }
            properties.add(overlay.build());
        }
        return properties;
    }

    private PropertyValue getDefaultFromText(String text, PropertyValue.PropertyType type)
            throws MojoExecutionException {
        switch (type) {
            case BOOLEAN:
                return new PropertyValue(Boolean.parseBoolean(text));
            case DOUBLE:
                return new PropertyValue(Double.parseDouble(text));
            case STRING:
                return new PropertyValue(text);
            default:
                throw new MojoExecutionException("Unexpected property type: " + type);
        }
    }

    private @Nullable PropertyConfig getPropertyConfig(@Nullable PluginConfig pluginConfig,
            String name) {
        if (pluginConfig == null) {
            return null;
        }
        for (PropertyConfig propertyConfig : pluginConfig.getProperties()) {
            if (name.equals(propertyConfig.getName())) {
                return propertyConfig;
            }
        }
        return null;
    }

    private static class PropertyDescriptorOverlay {

        private final String name;
        private final String label;
        private final PropertyValue.PropertyType type;
        private @Nullable PropertyValue defaultValue;
        private String description;

        private PropertyDescriptorOverlay(PropertyDescriptor base) {
            name = base.name();
            type = base.type();
            label = base.label();
            defaultValue = base.defaultValue();
            description = base.description();
        }

        private void setDefault(PropertyValue defaultValue) {
            this.defaultValue = defaultValue;
        }

        private void setDescription(String description) {
            this.description = description;
        }

        private PropertyDescriptor build() {
            return ImmutablePropertyDescriptor.builder()
                    .name(name)
                    .label(label)
                    .type(type)
                    .defaultValue(defaultValue)
                    .description(description).build();
        }
    }
}
