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
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import com.google.common.io.ByteStreams;

/**
 * @goal package
 * @phase process-classes
 * @requiresDependencyResolution compile
 * @requiresProject
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PackagerMojo extends AbstractMojo {

    /**
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    private int aopXmlCounter = 1;

    public void execute() throws MojoExecutionException {
        @SuppressWarnings("unchecked")
        List<Artifact> artifacts = project.getCompileArtifacts();
        File outputDir = new File(project.getBuild().getOutputDirectory());
        outputDir.mkdirs();
        if (!outputDir.isDirectory()) {
            throw new MojoExecutionException("Unable to create directory '" + outputDir
                    .getAbsolutePath() + "'");
        }
        List<byte[]> pluginPropertiesContents = new ArrayList<byte[]>();
        for (Artifact artifact : artifacts) {
            boolean informantCore = artifact.getGroupId().equals("org.informantproject")
                    && artifact.getArtifactId().equals("informant-core");
            try {
                explode(artifact.getFile(), outputDir, pluginPropertiesContents, informantCore);
            } catch (IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        if (!pluginPropertiesContents.isEmpty()) {
            try {
                writeMergedPluginsXml(pluginPropertiesContents, outputDir);
            } catch (IOException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

    private void explode(File jarFile, File outputDir, List<byte[]> pluginPropertiesContents,
            boolean informantCore) throws IOException, MojoExecutionException {

        JarInputStream jarIn = new JarInputStream(new FileInputStream(jarFile));
        JarEntry jarEntry;
        while ((jarEntry = jarIn.getNextJarEntry()) != null) {
            File outFile = new File(outputDir, jarEntry.getName());
            if (jarEntry.isDirectory()) {
                outFile.mkdirs();
                if (!outFile.isDirectory()) {
                    throw new MojoExecutionException("Unable to create directory '"
                            + outFile.getAbsolutePath() + "'");
                }
            } else if (jarEntry.getName().equals("META-INF/org.informantproject.plugin"
                    + ".properties")) {
                pluginPropertiesContents.add(ByteStreams.toByteArray(jarIn));
            } else if (!informantCore && jarEntry.getName().equals(
                    "META-INF/org.informantproject.aop.xml")) {
                ByteStreams.copy(jarIn, new FileOutputStream(new File(outputDir,
                        "META-INF/org.informantproject.aop." + aopXmlCounter + ".xml")));
                aopXmlCounter++;
            } else {
                ByteStreams.copy(jarIn, new FileOutputStream(outFile));
            }
        }
    }

    private void writeMergedPluginsXml(List<byte[]> pluginPropertiesContents,
            File outputDir) throws IOException {

        PrintWriter out = new PrintWriter(new File(outputDir,
                "META-INF/org.informantproject.package.xml"));

        out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.println("<plugins xmlns=\"http://www.informantproject.org/package/1.0\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
        out.println("  xsi:schemaLocation=\"http://www.informantproject.org/package/1.0"
                + " http://www.informantproject.org/xsd/package-1.0.xsd\">");
        out.println();
        for (byte[] pluginPropertiesContent : pluginPropertiesContents) {
            Properties pluginProperties = new Properties();
            pluginProperties.load(new ByteArrayInputStream(pluginPropertiesContent));
            String name = pluginProperties.getProperty("name");
            String groupId = pluginProperties.getProperty("groupId");
            String artifactId = pluginProperties.getProperty("artifactId");
            String version = pluginProperties.getProperty("version");
            out.println("  <plugin>");
            out.println("    <name>" + name + "</name>");
            out.println("    <groupId>" + groupId + "</groupId>");
            out.println("    <artifactId>" + artifactId + "</artifactId>");
            out.println("    <version>" + version + "</version>");
            out.println("  </plugin>");
        }
        out.println("</plugins>");
        out.close();
    }
}
