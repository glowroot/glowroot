/*
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
package org.glowroot.packager;

import java.io.IOException;
import java.util.List;

import checkers.nullness.quals.Nullable;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class PackagerMojo extends AbstractMojo {

    @Parameter(readonly = true, defaultValue = "${project}")
    @Nullable
    private MavenProject project;

    @Component
    @Nullable
    private MavenProjectHelper projectHelper;

    @Component
    @Nullable
    private ArtifactFactory artifactFactory;

    @Component
    @Nullable
    private ArtifactResolver artifactResolver;

    @Parameter(readonly = true, required = true,
            defaultValue = "${project.remoteArtifactRepositories}")
    @Nullable
    private List<ArtifactRepository> remoteArtifactRepositories;

    @Parameter(readonly = true, required = true, defaultValue = "${localRepository}")
    @Nullable
    private ArtifactRepository localRepository;

    @Parameter(readonly = true, required = true, defaultValue = "${project.build.finalName}")
    @Nullable
    private String finalName;

    @Parameter
    private PluginConfig/*@Nullable*/[] plugins;

    public void execute() throws MojoExecutionException {
        if (plugins == null) {
            plugins = new PluginConfig[0];
        }
        checkNotNull(project);
        checkNotNull(projectHelper);
        checkNotNull(artifactFactory);
        checkNotNull(artifactResolver);
        checkNotNull(remoteArtifactRepositories);
        checkNotNull(localRepository);
        checkNotNull(finalName);
        checkNotNull(plugins);
        Packager packager = new Packager(project, projectHelper, artifactFactory, artifactResolver,
                remoteArtifactRepositories, localRepository, finalName, plugins, getLog());
        try {
            packager.execute();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
