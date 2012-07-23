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
package org.informantproject.packager;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.informantproject.packager.PluginConfig.PropertyConfig;
import org.informantproject.packager.PluginDescriptor.PropertyDescriptor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

/**
 * @goal package
 * @phase package
 * @requiresDependencyResolution compile
 * @requiresProject
 * @threadSafe
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PackagerMojo extends AbstractMojo {

    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * @component
     * @required
     * @readonly
     */
    private MavenProjectHelper projectHelper;

    /**
     * @component
     * @required
     * @readonly
     */
    private ArtifactFactory artifactFactory;

    /**
     * @component
     * @required
     * @readonly
     */
    private ArtifactResolver artifactResolver;

    /**
     * @parameter default-value="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    private List<ArtifactRepository> remoteArtifactRepositories;

    /**
     * @parameter default-value="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * @parameter
     */
    private PluginConfig[] plugins;

    public void execute() throws MojoExecutionException {
        try {
            executeInternal();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        } catch (SAXException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    public void executeInternal() throws MojoExecutionException, IOException,
            ParserConfigurationException, SAXException {

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
    }

    private File getArtifactJarFile() {
        Artifact artifact = project.getArtifact();
        String artifactJarName = artifact.getArtifactId() + "-" + artifact.getVersion() + "."
                + artifact.getArtifactHandler().getExtension();
        return new File(project.getBuild().getDirectory(), artifactJarName);
    }

    private File getSourceJarFile() {
        Artifact artifact = project.getArtifact();
        String sourceArtifactJarName = artifact.getArtifactId() + "-" + artifact.getVersion()
                + "-sources." + artifact.getArtifactHandler().getExtension();
        return new File(project.getBuild().getDirectory(), sourceArtifactJarName);
    }

    private void createArtifactJar(List<Artifact> artifacts, File outputJarFile)
            throws MojoExecutionException, IOException, ParserConfigurationException, SAXException {

        Files.createParentDirs(outputJarFile);
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(outputJarFile),
                createManifest(artifacts));
        try {
            Set<String> seenDirectories = Sets.newHashSet();
            List<PluginDescriptor> pluginDescriptors = Lists.newArrayList();
            for (Artifact artifact : artifacts) {
                explode(artifact.getFile(), jarOut, seenDirectories, pluginDescriptors);
            }
            validateConfigForDuplicates();
            for (PluginConfig pluginConfig : plugins) {
                validateConfigItem(pluginDescriptors, pluginConfig);
            }
            if (!pluginDescriptors.isEmpty()) {
                writePackageXml(pluginDescriptors, jarOut);
            }
        } finally {
            jarOut.close();
        }
    }

    private Manifest createManifest(List<Artifact> artifacts) throws IOException,
            MojoExecutionException {

        for (Artifact artifact : artifacts) {
            if (artifact.getGroupId().equals("org.informantproject")
                    && artifact.getArtifactId().equals("informant-core")) {
                return new Manifest(new JarFile(artifact.getFile()).getManifest());
            }
        }
        throw new MojoExecutionException("org.informantproject:informant-core must be a"
                + " project dependency");
    }

    private void explode(File jarFile, JarOutputStream jarOut, Set<String> seenDirectories,
            List<PluginDescriptor> pluginDescriptors) throws IOException,
            ParserConfigurationException, SAXException {

        JarInputStream jarIn = new JarInputStream(new FileInputStream(jarFile));
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            if (jarEntry.isDirectory() && !seenDirectories.add(jarEntry.getName())) {
                continue;
            }
            if (jarEntry.getName().equals("META-INF/org.informantproject.plugin.xml")) {
                Document document = getDocument(new ByteArrayInputStream(
                        ByteStreams.toByteArray(jarIn)));
                PluginDescriptor pluginDescriptor = createPluginDescriptor(document
                        .getDocumentElement());
                pluginDescriptors.add(pluginDescriptor);
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

    private void validateConfigItem(List<PluginDescriptor> pluginDescriptors,
            PluginConfig pluginConfig) throws MojoExecutionException {

        PluginDescriptor pluginDescriptor = getPluginDescriptor(pluginConfig, pluginDescriptors);
        if (pluginDescriptor == null) {
            throw new MojoExecutionException("Found <plugin> tag under <configuration> that"
                    + " doesn't have a corresponding dependency in the pom file");
        }
        // check for property names with missing corresponding property name in property descriptor
        validateProperties(pluginConfig, pluginDescriptor);
    }

    @Nullable
    private PluginDescriptor getPluginDescriptor(PluginConfig pluginConfig,
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

    private void writePackageXml(List<PluginDescriptor> pluginDescriptors, JarOutputStream jarOut)
            throws IOException {

        JarEntry jarEntry = new JarEntry("META-INF/org.informantproject.package.xml");
        jarOut.putNextEntry(jarEntry);
        PrintWriter out = new PrintWriter(new OutputStreamWriter(jarOut, Charsets.UTF_8));
        out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.println("<package xmlns=\"http://www.informantproject.org/plugin/1.0\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
        out.println("  xsi:schemaLocation=\"http://www.informantproject.org/plugin/1.0"
                + " http://www.informantproject.org/xsd/package-1.0.xsd\">");
        out.println();
        out.println("  <plugins>");
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            out.println("    <plugin>");
            out.println("      <name>" + pluginDescriptor.getName() + "</name>");
            out.println("      <groupId>" + pluginDescriptor.getGroupId() + "</groupId>");
            out.println("      <artifactId>" + pluginDescriptor.getArtifactId() + "</artifactId>");
            out.println("      <version>" + pluginDescriptor.getVersion() + "</version>");
            writeProperties(out, pluginDescriptor);
            out.println("      <aspects>");
            for (String aspect : pluginDescriptor.getAspects()) {
                out.println("        <aspect>" + aspect + "</aspect>");
            }
            out.println("      </aspects>");
            out.println("    </plugin>");
        }
        out.println("  </plugins>");
        out.println("</package>");
        out.flush();
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

    private void writeProperties(PrintWriter out, PluginDescriptor pluginDescriptor) {
        if (pluginDescriptor.getProperties().isEmpty()) {
            return;
        }
        PluginConfig pluginConfig = getPluginConfig(pluginDescriptor.getId());
        out.println("      <properties>");
        for (PropertyDescriptor property : pluginDescriptor.getProperties()) {
            PropertyConfig override = getPropertyConfig(pluginConfig, property.getName());
            out.println("        <property>");
            if (override != null && override.getPrompt() != null) {
                out.println("          <prompt>" + override.getPrompt() + "</prompt>");
            } else {
                out.println("          <prompt>" + property.getPrompt() + "</prompt>");
            }
            out.println("          <name>" + property.getName() + "</name>");
            out.println("          <type>" + property.getType() + "</type>");
            if (override != null && override.getDefault() != null) {
                out.println("          <default>" + override.getDefault() + "</default>");
            } else if (property.getDefault() != null) {
                out.println("          <default>" + property.getDefault() + "</default>");
            }
            if (override != null && override.getHidden() != null) {
                out.println("          <hidden>" + override.getHidden() + "</hidden>");
            } else if (property.getHidden() != null) {
                out.println("          <hidden>" + property.getHidden() + "</hidden>");
            }
            if (override != null && override.getDescription() != null) {
                out.println("          <description>" + override.getDescription()
                        + "</description>");
            } else if (property.getDescription() != null) {
                out.println("          <description>" + property.getDescription()
                        + "</description>");
            }
            out.println("        </property>");
        }
        out.println("      </properties>");
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

    // TODO validate org.informantproject.plugin.xml files against schema
    // TODO reuse XmlDocuments from informant-core
    private static Document getDocument(InputStream inputStream)
            throws ParserConfigurationException, SAXException, IOException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(inputStream);
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
        String defaultValue = getOptionalElementText(propertyElement, "default");
        String hidden = getOptionalElementText(propertyElement, "hidden");
        String description = getOptionalElementText(propertyElement, "description");
        return new PropertyDescriptor(prompt, name, type, defaultValue, hidden, description);
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
