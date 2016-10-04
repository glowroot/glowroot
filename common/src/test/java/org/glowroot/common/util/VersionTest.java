/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.common.util;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VersionTest {

    @Test
    public void testWithNullManifest() {
        // when
        String version = Version.getVersion((Manifest) null);
        // then
        assertThat(version).isEqualTo("unknown");
    }

    @Test
    public void testWithNoImplementationVersion() {
        // given
        Manifest manifest = mock(Manifest.class);
        Attributes attributes = mock(Attributes.class);
        when(manifest.getMainAttributes()).thenReturn(attributes);
        // when
        String version = Version.getVersion(manifest);
        // then
        assertThat(version).isEqualTo("unknown");
    }

    @Test
    public void testWithNonSnapshotVersion() {
        // given
        Manifest manifest = mock(Manifest.class);
        Attributes attributes = mock(Attributes.class);
        when(manifest.getMainAttributes()).thenReturn(attributes);
        when(attributes.getValue("Implementation-Version")).thenReturn("0.1.2");
        // when
        String version = Version.getVersion(manifest);
        // then
        assertThat(version).isEqualTo("0.1.2");
    }

    @Test
    public void testWithSnapshotVersion() {
        // given
        Manifest manifest = mock(Manifest.class);
        Attributes attributes = mock(Attributes.class);
        when(manifest.getMainAttributes()).thenReturn(attributes);
        when(attributes.getValue("Implementation-Version")).thenReturn("0.1.2-SNAPSHOT");
        when(attributes.getValue("Build-Commit"))
                .thenReturn("0123456789abcdeabcde0123456789abcdeabcde");
        when(attributes.getValue("Build-Time")).thenReturn("xyz");
        // when
        String version = Version.getVersion(manifest);
        // then
        assertThat(version).isEqualTo("0.1.2-SNAPSHOT, commit 0123456789, built xyz");
    }

    @Test
    public void testWithNoneBuildCommit() {
        // given
        Manifest manifest = mock(Manifest.class);
        Attributes attributes = mock(Attributes.class);
        when(manifest.getMainAttributes()).thenReturn(attributes);
        when(attributes.getValue("Implementation-Version")).thenReturn("0.1.2-SNAPSHOT");
        when(attributes.getValue("Build-Commit")).thenReturn("[none]");
        when(attributes.getValue("Build-Time")).thenReturn("xyz");
        // when
        String version = Version.getVersion(manifest);
        // then
        assertThat(version).isEqualTo("0.1.2-SNAPSHOT, built xyz");
    }

    @Test
    public void testWithInvalidBuildCommit() {
        // given
        Manifest manifest = mock(Manifest.class);
        Attributes attributes = mock(Attributes.class);
        when(manifest.getMainAttributes()).thenReturn(attributes);
        when(attributes.getValue("Implementation-Version")).thenReturn("0.1.2-SNAPSHOT");
        when(attributes.getValue("Build-Commit")).thenReturn("tooshort");
        when(attributes.getValue("Build-Time")).thenReturn("xyz");
        // when
        String version = Version.getVersion(manifest);
        // then
        assertThat(version).isEqualTo("0.1.2-SNAPSHOT, built xyz");
    }

    @Test
    public void testWithMissingBuildTime() {
        // given
        Manifest manifest = mock(Manifest.class);
        Attributes attributes = mock(Attributes.class);
        when(manifest.getMainAttributes()).thenReturn(attributes);
        when(attributes.getValue("Implementation-Version")).thenReturn("0.1.2-SNAPSHOT");
        when(attributes.getValue("Build-Commit")).thenReturn("tooshort");
        // when
        String version = Version.getVersion(manifest);
        // then
        assertThat(version).isEqualTo("0.1.2-SNAPSHOT");
    }

    @Test
    public void testWithInvalidBuildCommitAndMissingBuildTime() {
        // given
        Manifest manifest = mock(Manifest.class);
        Attributes attributes = mock(Attributes.class);
        when(manifest.getMainAttributes()).thenReturn(attributes);
        when(attributes.getValue("Implementation-Version")).thenReturn("0.1.2-SNAPSHOT");
        when(attributes.getValue("Build-Commit")).thenReturn("tooshort");
        // when
        String version = Version.getVersion(manifest);
        // then
        assertThat(version).isEqualTo("0.1.2-SNAPSHOT");
    }

    @Test
    public void testClassWithNoClassFile() throws IOException {
        assertThat(Version.getManifest(long.class)).isNull();
    }
}
