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
package io.informant.packager;

import io.informant.core.config.PluginInfo;
import io.informant.core.config.PluginInfoReader;
import io.informant.core.config.PropertyDescriptor;
import io.informant.packager.PluginConfig.PropertyConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.shade.pom.PomWriter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.WriterFactory;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonWriter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class PackagerMojo extends AbstractMojo {

    private static final Gson gson = new Gson();

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    @Component
    private ArtifactFactory artifactFactory;

    @Component
    private ArtifactResolver artifactResolver;

    @Parameter(readonly = true, required = true,
            defaultValue = "${project.remoteArtifactRepositories}")
    private List<ArtifactRepository> remoteArtifactRepositories;

    @Parameter(readonly = true, required = true, defaultValue = "${localRepository}")
    private ArtifactRepository localRepository;

    @Parameter(readonly = true, required = true, defaultValue = "${project.build.finalName}")
    private String finalName;

    @Parameter
    private PluginConfig[] plugins;

    public void execute() throws MojoExecutionException {
        try {
            executeInternal();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (JsonSyntaxException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    public void executeInternal() throws MojoExecutionException, IOException, JsonSyntaxException {
        if (plugins == null) {
            plugins = new PluginConfig[0];
        }
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
                getLog().warn("Could not get sources for " + artifact);
            }
            if (sourceArtifact.isResolved()) {
                sourceArtifacts.add(sourceArtifact);
            } else {
                getLog().warn("Could not get sources for " + artifact);
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
            throws MojoExecutionException, IOException, JsonSyntaxException {
        Files.createParentDirs(outputJarFile);
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(outputJarFile),
                createManifest(artifacts));
        try {
            Set<String> seenDirectories = Sets.newHashSet();
            List<PluginInfo> pluginInfos = Lists.newArrayList();
            for (Artifact artifact : artifacts) {
                explode(artifact.getFile(), jarOut, seenDirectories, pluginInfos);
            }
            validateConfigForDuplicates();
            for (PluginConfig pluginConfig : plugins) {
                validateConfigItem(pluginInfos, pluginConfig);
            }
            if (!pluginInfos.isEmpty()) {
                writePackageJson(pluginInfos, jarOut);
            }
        } finally {
            jarOut.close();
        }
    }

    private Manifest createManifest(List<Artifact> artifacts) throws IOException,
            MojoExecutionException {
        for (Artifact artifact : artifacts) {
            if (artifact.getGroupId().equals("io.informant")
                    && artifact.getArtifactId().equals("informant-core")) {
                JarFile jarFile = new JarFile(artifact.getFile());
                Manifest manifest = jarFile.getManifest();
                jarFile.close();
                return new Manifest(manifest);
            }
        }
        throw new MojoExecutionException("Missing project dependency io.informant:informant-core");
    }

    private void explode(File jarFile, JarOutputStream jarOut, Set<String> seenDirectories,
            List<PluginInfo> pluginInfos) throws IOException, JsonSyntaxException {

        JarInputStream jarIn = new JarInputStream(new FileInputStream(jarFile));
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            if (jarEntry.isDirectory() && !seenDirectories.add(jarEntry.getName())) {
                continue;
            }
            if (jarEntry.getName().equals("META-INF/io.informant.plugin.json")) {
                String pluginJson = CharStreams.toString(new InputStreamReader(jarIn,
                        Charsets.UTF_8));
                pluginInfos.add(PluginInfoReader.createPluginInfo(pluginJson));
            } else {
                JarEntry jarOutEntry = new JarEntry(jarEntry.getName());
                jarOut.putNextEntry(jarOutEntry);
                ByteStreams.copy(jarIn, jarOut);
            }
        }
    }

    private void validateConfigForDuplicates() throws MojoExecutionException {
        for (PluginConfig pluginConfig : plugins) {
            for (PluginConfig pluginConfig2 : plugins) {
                if (pluginConfig != pluginConfig2
                        && pluginConfig.getId().equals(pluginConfig2.getId())) {
                    throw new MojoExecutionException("Found duplicate <plugin> tags (same groupId"
                            + " and artifactId) under <configuration>");
                }
            }
        }
    }

    private void validateConfigItem(List<PluginInfo> pluginInfos, PluginConfig pluginConfig)
            throws MojoExecutionException {
        PluginInfo pluginInfo = getPluginInfo(pluginConfig, pluginInfos);
        if (pluginInfo == null) {
            throw new MojoExecutionException("Found <plugin> tag under <configuration> that"
                    + " doesn't have a corresponding dependency in the pom file");
        }
        // check for property names with missing corresponding property name in property descriptor
        validateProperties(pluginConfig, pluginInfo);
    }

    @Nullable
    private PluginInfo getPluginInfo(PluginConfig pluginConfig, List<PluginInfo> pluginInfos) {
        for (PluginInfo pluginInfo : pluginInfos) {
            if (pluginInfo.getId().equals(pluginConfig.getId())) {
                return pluginInfo;
            }
        }
        return null;
    }

    private void validateProperties(PluginConfig pluginConfig, PluginInfo pluginInfo)
            throws MojoExecutionException {
        for (PropertyConfig propertyConfig : pluginConfig.getProperties()) {
            String propertyName = propertyConfig.getName();
            if (propertyName == null || propertyName.length() == 0) {
                throw new MojoExecutionException("Missing or empty <name> under"
                        + " <configuration>/<plugins>/<plugin>/<properties>/<property>");
            }
            boolean found = false;
            for (PropertyDescriptor propertyDescriptor : pluginInfo.getPropertyDescriptors()) {
                if (propertyDescriptor.getName().equals(propertyName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new MojoExecutionException("Found <property> tag with name '" + propertyName
                        + "' under <configuration>/<plugins>/<plugin>/<properties> that doesn't"
                        + " have a corresponding property defined in the plugin '"
                        + pluginInfo.getId() + "'");
            }
        }
    }

    private void writePackageJson(List<PluginInfo> pluginInfos, JarOutputStream jarOut)
            throws IOException, MojoExecutionException {
        JarEntry jarEntry = new JarEntry("META-INF/io.informant.package.json");
        jarOut.putNextEntry(jarEntry);
        JsonObject rootElement = new JsonObject();
        JsonArray pluginElements = new JsonArray();
        for (PluginInfo pluginInfo : pluginInfos) {
            JsonObject pluginElement = new JsonObject();
            pluginElement.addProperty("name", pluginInfo.getName());
            pluginElement.addProperty("groupId", pluginInfo.getGroupId());
            pluginElement.addProperty("artifactId", pluginInfo.getArtifactId());
            pluginElement.addProperty("version", pluginInfo.getVersion());
            pluginElement.add("properties", getPropertyElements(pluginInfo));
            pluginElement.add("aspects", getAspectElements(pluginInfo));
            pluginElements.add(pluginElement);
        }
        rootElement.add("plugins", pluginElements);
        JsonWriter jw = new JsonWriter(new OutputStreamWriter(jarOut, Charsets.UTF_8));
        gson.toJson(rootElement, jw);
        jw.flush();
        jarOut.closeEntry();
    }

    @Nullable
    private PluginConfig getPluginConfig(String id) {
        for (PluginConfig pluginConfig : plugins) {
            if (pluginConfig.getId().equals(id)) {
                return pluginConfig;
            }
        }
        return null;
    }

    private JsonArray getPropertyElements(PluginInfo pluginInfo) throws MojoExecutionException {
        JsonArray propertyElements = new JsonArray();
        PluginConfig pluginConfig = getPluginConfig(pluginInfo.getId());
        for (PropertyDescriptor propertyDescriptor : pluginInfo.getPropertyDescriptors()) {
            PropertyConfig override = getPropertyConfig(pluginConfig, propertyDescriptor.getName());
            JsonObject propertyObject = new JsonObject();
            propertyObject.addProperty("prompt", getPrompt(propertyDescriptor, override));
            propertyObject.addProperty("name", propertyDescriptor.getName());
            propertyObject.addProperty("type", propertyDescriptor.getType().name()
                    .toLowerCase(Locale.ENGLISH));
            propertyObject.add("default", getDefault(propertyDescriptor, override));
            propertyObject.addProperty("hidden", getHidden(propertyDescriptor, override));
            propertyObject.addProperty("description",
                    getDescription(propertyDescriptor, override));
            propertyElements.add(propertyObject);
        }
        return propertyElements;
    }

    private JsonArray getAspectElements(PluginInfo pluginInfo) {
        JsonArray aspectElements = new JsonArray();
        for (String aspect : pluginInfo.getAspects()) {
            aspectElements.add(new JsonPrimitive(aspect));
        }
        return aspectElements;
    }

    private String getPrompt(PropertyDescriptor propertyDescriptor, PropertyConfig override) {
        if (override != null && override.getPrompt() != null) {
            return override.getPrompt();
        } else {
            return propertyDescriptor.getPrompt();
        }
    }

    @Nullable
    private JsonPrimitive getDefault(PropertyDescriptor propertyDescriptor, PropertyConfig override)
            throws MojoExecutionException {
        if (override != null && override.getDefault() != null) {
            String defaultText = override.getDefault();
            switch (propertyDescriptor.getType()) {
            case STRING:
                return new JsonPrimitive(defaultText);
            case BOOLEAN:
                return new JsonPrimitive(Boolean.valueOf(defaultText));
            case DOUBLE:
                return new JsonPrimitive(Double.valueOf(defaultText));
            default:
                throw new MojoExecutionException("Unexpected property type: "
                        + propertyDescriptor.getType());
            }
        } else {
            Object defaultValue = propertyDescriptor.getDefault();
            if (defaultValue == null) {
                return null;
            } else if (defaultValue instanceof String) {
                return new JsonPrimitive((String) defaultValue);
            } else if (defaultValue instanceof Boolean) {
                return new JsonPrimitive((Boolean) defaultValue);
            } else if (defaultValue instanceof Double) {
                return new JsonPrimitive((Double) defaultValue);
            } else {
                throw new MojoExecutionException("Unexpected default value type: "
                        + defaultValue.getClass().getName());
            }
        }
    }

    private boolean getHidden(PropertyDescriptor propertyDescriptor, PropertyConfig override) {
        if (override != null && override.getHidden() != null) {
            return Boolean.valueOf(override.getHidden());
        } else {
            return propertyDescriptor.isHidden();
        }
    }

    private String getDescription(PropertyDescriptor propertyDescriptor, PropertyConfig override) {
        if (override != null && override.getDescription() != null) {
            return override.getDescription();
        } else {
            return propertyDescriptor.getDescription();
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
        if (dependencyReducedPomLocation.exists()) {
            dependencyReducedPomLocation.delete();
        }
        Writer w = WriterFactory.newXmlWriter(dependencyReducedPomLocation);
        PomWriter.write(w, model, true);
        project.setFile(dependencyReducedPomLocation);
    }
}
