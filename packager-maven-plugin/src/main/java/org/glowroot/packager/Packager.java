/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.packager;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.shade.pom.PomWriter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.WriterFactory;
import org.glowroot.agent.config.*;
import org.glowroot.agent.shaded.fasterxml.jackson.core.JsonGenerator;
import org.glowroot.agent.shaded.fasterxml.jackson.databind.ObjectMapper;
import org.glowroot.agent.shaded.fasterxml.jackson.databind.module.SimpleModule;
import org.glowroot.agent.shaded.glowroot.common.util.ObjectMappers;
import org.glowroot.packager.PluginConfig.PropertyConfig;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Packager {

    private final MavenProject project;
    private final MavenProjectHelper projectHelper;
    private final ArtifactFactory artifactFactory;
    private final ArtifactResolver artifactResolver;
    private final List<ArtifactRepository> remoteArtifactRepositories;
    private final ArtifactRepository localRepository;
    private final String finalName;
    private final PluginConfig[] pluginConfigs;
    private final Log log;
    private final ObjectMapper mapper;
    private final Set<String> excludes;
    private final Map<String, File> overrides;

    public Packager(MavenProject project, MavenProjectHelper projectHelper,
                    ArtifactFactory artifactFactory, ArtifactResolver artifactResolver,
                    List<ArtifactRepository> remoteArtifactRepositories,
                    ArtifactRepository localRepository, String finalName, PluginConfig[] pluginConfigs,
                    Log log, Set<String> excludes, Set<String> overrides) {
        this.project = project;
        this.projectHelper = projectHelper;
        this.artifactFactory = artifactFactory;
        this.artifactResolver = artifactResolver;
        this.remoteArtifactRepositories = remoteArtifactRepositories;
        this.localRepository = localRepository;
        this.finalName = finalName;
        this.pluginConfigs = pluginConfigs;
        this.log = log;
        this.mapper = ObjectMappers.create();
        this.excludes = prefixExcludes(excludes);
        this.overrides = buildOverrides(overrides);
        addMappers();
    }



    public void execute() throws MojoExecutionException, IOException {
        List<Artifact> artifacts = project.getCompileArtifacts();
        createArtifactJar(artifacts, getArtifactJarFile());

        List<Artifact> sourceArtifacts = Lists.newArrayList();
        for (Artifact artifact : artifacts) {
            Artifact sourceArtifact = artifactFactory.createArtifactWithClassifier(
                    artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                    "java-source", "sources");
            try {
                artifactResolver.resolve(sourceArtifact, remoteArtifactRepositories,
                        localRepository);
            } catch (ArtifactResolutionException e) {
                // ignore, the jar has not been found
            } catch (ArtifactNotFoundException e) {
                log.warn("Could not get sources for " + artifact);
            }
            if (sourceArtifact.isResolved()) {
                sourceArtifacts.add(sourceArtifact);
            } else {
                log.warn("Could not get sources for " + artifact);
            }
        }
        File sourceJarFile = getSourceJarFile();
        createArtifactJar(sourceArtifacts, sourceJarFile);
        projectHelper.attachArtifact(project, "jar", "sources", sourceJarFile);
        // remove dependencies from pom since all dependencies are now embedded directly in artifact
        createDependencyReducedPom();
    }

    private Map<String, File> buildOverrides(Set<String> overrides) {
        Map<String, File> result = new HashMap<String, File>();
        for (String override : overrides) {
            for (Resource resource : (List<Resource>) project.getResources()) {
                File file = new File(resource.getDirectory(), override);
                if (file.exists()) {
                    result.put(override, file);
                } else {
                    log.info(String.format("Override %s not found in %s", override, file.getAbsoluteFile()));
                }
            }

        }
        return result;
    }

    private Set<String> prefixExcludes(Set<String> excludes) {
        Set<String> result = Sets.newHashSet();
        for (String exclude : excludes) {
            result.add("org:glowroot:agent:shaded:" + exclude);
        }
        return result;
    }

    private void addMappers() {
        SimpleModule module = new SimpleModule();
        module.addAbstractTypeMapping(InstrumentationConfig.class,
                ImmutableInstrumentationConfig.class);
        module.addAbstractTypeMapping(PropertyDescriptor.class, ImmutablePropertyDescriptor.class);
        mapper.registerModule(module);
        mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
    }

    private File getArtifactJarFile() {
        return new File(project.getBuild().getDirectory(), finalName + ".jar");
    }

    private File getSourceJarFile() {
        return new File(project.getBuild().getDirectory(), finalName + "-sources.jar");
    }

    private void createArtifactJar(List<Artifact> artifacts, File outputJarFile)
            throws MojoExecutionException, IOException {
        Files.createParentDirs(outputJarFile);
        FileOutputStream fileOut = new FileOutputStream(outputJarFile);
        JarOutputStream jarOut = null;
        try {
            jarOut = new JarOutputStream(fileOut);
            writeMetaInfFiles(artifacts, jarOut);
            Set<String> seenDirectories = Sets.newHashSet();
            List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
            for (Artifact artifact : artifacts) {
                explode(artifact.getFile(), jarOut, seenDirectories, pluginDescriptors);
            }
            validateConfigForDuplicates();
            for (PluginConfig pluginConfig : pluginConfigs) {
                validateConfigItem(pluginDescriptors, pluginConfig);
            }
            if (!pluginDescriptors.isEmpty()) {
                JarEntry jarEntry = new JarEntry("META-INF/glowroot.plugins.json");
                jarOut.putNextEntry(jarEntry);
                writePlugins(pluginDescriptors, jarOut);
                jarOut.closeEntry();
            }
        } finally {
            if (jarOut == null) {
                fileOut.close();
            } else {
                jarOut.close();
            }
        }
    }

    private void writeMetaInfFiles(List<Artifact> artifacts, JarOutputStream jarOut)
            throws IOException, MojoExecutionException {
        for (Artifact artifact : artifacts) {
            if (artifact.getGroupId().equals("org.glowroot")
                    && artifact.getArtifactId().equals("glowroot-agent")) {
                JarFile jarFile = new JarFile(artifact.getFile());
                writeFile("META-INF/MANIFEST.MF", jarFile, jarOut);
                writeFile("META-INF/LICENSE", jarFile, jarOut);
                writeFile("META-INF/NOTICE", jarFile, jarOut);
                jarFile.close();
                return;
            }
        }
        throw new MojoExecutionException("Missing project dependency org.glowroot:glowroot-agent");
    }

    private void writeFile(String name, JarFile jarFile, JarOutputStream jarOut)
            throws IOException {
        JarEntry jarEntryIn = jarFile.getJarEntry(name);
        InputStream in = jarFile.getInputStream(jarEntryIn);
        byte[] bytes = ByteStreams.toByteArray(in);
        in.close();
        JarEntry jarEntryOut = new JarEntry(name);
        jarOut.putNextEntry(jarEntryOut);
        jarOut.write(bytes);
        jarOut.closeEntry();
    }

    private void explode(File jarFile, JarOutputStream jarOut, Set<String> seenDirectories,
                         List<PluginDescriptor> pluginDescriptors) throws IOException {
        JarInputStream jarIn = new JarInputStream(new FileInputStream(jarFile));
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            String name = jarEntry.getName();
            if (jarEntry.isDirectory() && !seenDirectories.add(name)) {
                continue;
            }
            if (name.equals("META-INF/glowroot.plugin.json")) {
                String content = CharStreams.toString(new InputStreamReader(jarIn, Charsets.UTF_8));
                PluginDescriptor pluginDescriptor =
                        mapper.readValue(content, ImmutablePluginDescriptor.class);
                pluginDescriptors.add(pluginDescriptor);
            } else if (name.equals("META-INF/LICENSE") || name.equals("META-INF/NOTICE")) {
                // do nothing, already copied LICENSE and NOTICE from glowroot-core above
            } else if (isExcluded(name)) {
                // do nothing, we don't what that file
            } else if (mustOverride(name)) {
                JarEntry jarOutEntry = new JarEntry(name);
                jarOut.putNextEntry(jarOutEntry);
                FileInputStream from = new FileInputStream(overrides.get(name));
                ByteStreams.copy(from, jarOut);
                from.close();
            } else {
                JarEntry jarOutEntry = new JarEntry(name);
                jarOut.putNextEntry(jarOutEntry);
                ByteStreams.copy(jarIn, jarOut);
            }
        }
    }

    private boolean mustOverride(String name) {
        return overrides.containsKey(name);
    }

    private boolean isExcluded(String name) {
        if (!excludes.isEmpty()) {
            String n = name.replace('/', ':');
            for (String exclude : excludes) {
                if (exclude.endsWith("*")) {
                    if (n.startsWith(exclude.substring(0, exclude.length() - 1))) {
                        return true;
                    }
                } else {
                    if (n.equals(exclude)) {
                        return true;
                    }
                }
            }

        }
        return false;
    }

    private void validateConfigForDuplicates() throws MojoExecutionException {
        for (PluginConfig pluginConfig : pluginConfigs) {
            for (PluginConfig pluginConfig2 : pluginConfigs) {
                if (pluginConfig != pluginConfig2
                        && Objects.equal(pluginConfig.getId(), pluginConfig2.getId())) {
                    throw new MojoExecutionException("Found duplicate <plugin> tags (same groupId"
                            + " and artifactId) under <configuration>");
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


    private PluginDescriptor getPlugin(PluginConfig pluginConfig,
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

    private void writePlugins(List<PluginDescriptor> pluginDescriptors, OutputStream out)
            throws IOException, MojoExecutionException {
        List<PluginDescriptor> updatedPlugins = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            ImmutablePluginDescriptor updatedPlugin = ImmutablePluginDescriptor.copyOf(pluginDescriptor).
                    withProperties(getPropertiesWithOverrides(pluginDescriptor));
            updatedPlugins.add(updatedPlugin);
        }
        // because of jackson shading, must perform serialization inside glowroot "shaded" code
        mapper.writeValue(out, updatedPlugins);
    }


    private PluginConfig getPluginConfig(String id) {
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


    private PropertyConfig getPropertyConfig(PluginConfig pluginConfig, String name) {
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

    private void createDependencyReducedPom() throws IOException {
        Model model = project.getOriginalModel();
        model.setDependencies(Lists.<Dependency>newArrayList());
        File dependencyReducedPomLocation = new File(project.getBuild().getDirectory(),
                "dependency-reduced-pom.xml");
        if (dependencyReducedPomLocation.exists() && !dependencyReducedPomLocation.delete()) {
            throw new IOException("Could not delete file '"
                    + dependencyReducedPomLocation.getCanonicalPath() + "'");
        }
        Writer w = WriterFactory.newXmlWriter(dependencyReducedPomLocation);
        PomWriter.write(w, model, true);
        project.setFile(dependencyReducedPomLocation);
    }

    private static class PropertyDescriptorOverlay {

        private final String name;
        private final String label;
        private final PropertyValue.PropertyType type;
        private PropertyValue defaultValue;
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
            return ImmutablePropertyDescriptor.builder().
                    name(name).
                    label(label).
                    type(type).
                    defaultValue(defaultValue).
                    description(description).build();
        }
    }
}
