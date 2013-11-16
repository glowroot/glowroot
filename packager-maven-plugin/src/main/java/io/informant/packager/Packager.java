/*
 * Copyright 2013 the original author or authors.
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
package io.informant.packager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Charsets;
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
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.shade.pom.PomWriter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.WriterFactory;

import io.informant.config.PackageDescriptor;
import io.informant.config.PluginDescriptor;
import io.informant.config.PropertyDescriptor;
import io.informant.config.PropertyDescriptor.PropertyType;
import io.informant.packager.PluginConfig.PropertyConfig;

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

    public Packager(MavenProject project, MavenProjectHelper projectHelper,
            ArtifactFactory artifactFactory, ArtifactResolver artifactResolver,
            List<ArtifactRepository> remoteArtifactRepositories,
            ArtifactRepository localRepository, String finalName, PluginConfig[] pluginConfigs,
            Log log) {
        this.project = project;
        this.projectHelper = projectHelper;
        this.artifactFactory = artifactFactory;
        this.artifactResolver = artifactResolver;
        this.remoteArtifactRepositories = remoteArtifactRepositories;
        this.localRepository = localRepository;
        this.finalName = finalName;
        this.pluginConfigs = pluginConfigs;
        this.log = log;
    }

    public void execute() throws MojoExecutionException, IOException {
        @SuppressWarnings("unchecked")
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
            JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
            jarOut.putNextEntry(manifestEntry);
            jarOut.write(createManifest(artifacts));
            jarOut.closeEntry();
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
                JarEntry jarEntry = new JarEntry("META-INF/io.informant.package.json");
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

    private byte[] createManifest(List<Artifact> artifacts) throws IOException,
            MojoExecutionException {
        for (Artifact artifact : artifacts) {
            if (artifact.getGroupId().equals("io.informant")
                    && artifact.getArtifactId().equals("informant-core")) {
                JarFile jarFile = new JarFile(artifact.getFile());
                JarEntry manifestEntry = jarFile.getJarEntry("META-INF/MANIFEST.MF");
                InputStream manifestIn = jarFile.getInputStream(manifestEntry);
                byte[] manifestBytes = ByteStreams.toByteArray(manifestIn);
                manifestIn.close();
                jarFile.close();
                return manifestBytes;
            }
        }
        throw new MojoExecutionException("Missing project dependency io.informant:informant");
    }

    private void explode(File jarFile, JarOutputStream jarOut, Set<String> seenDirectories,
            List<PluginDescriptor> pluginDescriptors) throws IOException {
        JarInputStream jarIn = new JarInputStream(new FileInputStream(jarFile));
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            if (jarEntry.isDirectory() && !seenDirectories.add(jarEntry.getName())) {
                continue;
            }
            if (jarEntry.getName().equals("META-INF/io.informant.plugin.json")) {
                String content = CharStreams.toString(new InputStreamReader(jarIn, Charsets.UTF_8));
                pluginDescriptors.add(PluginDescriptor.readValue(content));
            } else {
                JarEntry jarOutEntry = new JarEntry(jarEntry.getName());
                jarOut.putNextEntry(jarOutEntry);
                ByteStreams.copy(jarIn, jarOut);
            }
        }
    }

    private void validateConfigForDuplicates() throws MojoExecutionException {
        for (PluginConfig pluginConfig : pluginConfigs) {
            for (PluginConfig pluginConfig2 : pluginConfigs) {
                if (pluginConfig != pluginConfig2
                        && pluginConfig.getId().equals(pluginConfig2.getId())) {
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

    @Nullable
    private PluginDescriptor getPlugin(PluginConfig pluginConfig,
            List<PluginDescriptor> pluginDescriptors) {
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            if (pluginDescriptor.getId().equals(pluginConfig.getId())) {
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
            for (PropertyDescriptor propertyDescriptor : pluginDescriptor.getProperties()) {
                if (propertyDescriptor.getName().equals(propertyName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new MojoExecutionException("Found <property> tag with name '" + propertyName
                        + "' under <configuration>/<plugins>/<plugin>/<properties> that doesn't"
                        + " have a corresponding property defined in the plugin '"
                        + pluginDescriptor.getId() + "'");
            }
        }
    }

    private void writePlugins(List<PluginDescriptor> pluginDescriptors, OutputStream out)
            throws IOException, MojoExecutionException {
        List<PluginDescriptor> updatedPlugins = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            PluginDescriptor.Builder updatedPlugin = PluginDescriptor.builder(pluginDescriptor);
            updatedPlugin.properties(getPropertiesWithOverrides(pluginDescriptor));
            updatedPlugins.add(updatedPlugin.build());
        }
        // because of jackson shading, must perform serialization inside informant "shaded" code
        new PackageDescriptor(updatedPlugins).writeValue(out);
    }

    @Nullable
    private PluginConfig getPluginConfig(String id) {
        for (PluginConfig pluginConfig : pluginConfigs) {
            if (pluginConfig.getId().equals(id)) {
                return pluginConfig;
            }
        }
        return null;
    }

    private List<PropertyDescriptor> getPropertiesWithOverrides(PluginDescriptor pluginDescriptor)
            throws MojoExecutionException {
        List<PropertyDescriptor> properties = Lists.newArrayList();
        PluginConfig pluginConfig = getPluginConfig(pluginDescriptor.getId());
        for (PropertyDescriptor property : pluginDescriptor.getProperties()) {
            PropertyConfig override = getPropertyConfig(pluginConfig, property.getName());
            if (override == null) {
                properties.add(property);
                continue;
            }
            PropertyDescriptorOverlay overlay = new PropertyDescriptorOverlay(property);
            String overrideDefault = override.getDefault();
            String overrideHidden = override.getHidden();
            String overridePrompt = override.getPrompt();
            String overrideDescription = override.getDescription();
            if (overrideDefault != null) {
                overlay.setDefault(getDefaultFromText(overrideDefault, property.getType()));
            }
            if (overrideHidden != null) {
                overlay.setHidden(Boolean.valueOf(overrideHidden));
            }
            if (overridePrompt != null) {
                overlay.setPrompt(overridePrompt);
            }
            if (overrideDescription != null) {
                overlay.setDescription(overrideDescription);
            }
            properties.add(overlay.build());
        }
        return properties;
    }

    private Object getDefaultFromText(String text, PropertyType type)
            throws MojoExecutionException {
        switch (type) {
            case STRING:
                return text;
            case BOOLEAN:
                return Boolean.valueOf(text);
            case DOUBLE:
                return Double.valueOf(text);
            default:
                throw new MojoExecutionException("Unexpected property type: " + type);
        }
    }

    @Nullable
    private PropertyConfig getPropertyConfig(@Nullable PluginConfig pluginConfig, String name) {
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
        model.setDependencies(Lists.newArrayList());
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
        private final PropertyType type;
        @Nullable
        private Object defaultValue;
        private boolean hidden;
        @Nullable
        private String prompt;
        @Nullable
        private String description;

        private PropertyDescriptorOverlay(PropertyDescriptor base) {
            name = base.getName();
            type = base.getType();
            defaultValue = base.getDefault();
            hidden = base.isHidden();
            prompt = base.getPrompt();
            description = base.getDescription();
        }

        private void setDefault(Object defaultValue) {
            this.defaultValue = defaultValue;
        }

        private void setHidden(boolean hidden) {
            this.hidden = hidden;
        }

        private void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        private void setDescription(String description) {
            this.description = description;
        }

        private PropertyDescriptor build() {
            return PropertyDescriptor.create(name, hidden, prompt, description, type, defaultValue);
        }
    }
}
