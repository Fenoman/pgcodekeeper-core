/*
 * Copyright 2015-2026 TAXTELECOM, LLC
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
package org.pgcodekeeper.core.it.release;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

class ReleaseIdentityIT {

    private static final String EXPECTED_GROUP_ID = "org.pgcodekeeper";
    private static final String EXPECTED_ARTIFACT_ID = "pgcodekeeper-core";
    private static final String EXPECTED_MAVEN_VERSION = "15.3.0-neo1";
    private static final String EXPECTED_BUNDLE_SYMBOLIC_NAME = "org.pgcodekeeper.core";
    private static final String EXPECTED_BUNDLE_VERSION = "15.3.0.neo1";
    private static final String POM_PROPERTIES = "META-INF/maven/" + EXPECTED_GROUP_ID + '/'
            + EXPECTED_ARTIFACT_ID + "/pom.properties";

    @Test
    void packagedBundleHasUniqueNeoIdentity() throws IOException {
        String projectGroupId = requiredSystemProperty("project.groupId");
        String projectArtifactId = requiredSystemProperty("project.artifactId");
        String projectVersion = requiredSystemProperty("project.version");
        String finalName = requiredSystemProperty("project.build.finalName");
        Path jarPath = Path.of(requiredSystemProperty("project.build.directory"), finalName + ".jar");

        assertTrue(Files.isRegularFile(jarPath), () -> "Missing packaged JAR: " + jarPath);
        assertFalse(Files.isSymbolicLink(jarPath), () -> "Packaged JAR must not be a symlink: " + jarPath);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, () -> "Missing manifest in " + jarPath);
            Attributes attributes = manifest.getMainAttributes();

            JarEntry pomPropertiesEntry = jar.getJarEntry(POM_PROPERTIES);
            assertNotNull(pomPropertiesEntry, () -> "Missing " + POM_PROPERTIES + " in " + jarPath);
            Properties coordinates = new Properties();
            try (InputStream in = jar.getInputStream(pomPropertiesEntry)) {
                coordinates.load(in);
            }

            assertAll("release identity",
                    () -> assertEquals(EXPECTED_GROUP_ID, projectGroupId, "Maven project groupId"),
                    () -> assertEquals(EXPECTED_ARTIFACT_ID, projectArtifactId, "Maven project artifactId"),
                    () -> assertEquals(EXPECTED_MAVEN_VERSION, projectVersion, "Maven project version"),
                    () -> assertEquals(EXPECTED_ARTIFACT_ID + '-' + EXPECTED_MAVEN_VERSION, finalName,
                            "Maven finalName"),
                    () -> assertEquals(EXPECTED_BUNDLE_SYMBOLIC_NAME,
                            attributes.getValue("Bundle-SymbolicName"), "OSGi Bundle-SymbolicName"),
                    () -> assertEquals(EXPECTED_BUNDLE_VERSION,
                            attributes.getValue("Bundle-Version"), "OSGi Bundle-Version"),
                    () -> assertEquals(EXPECTED_MAVEN_VERSION,
                            attributes.getValue("Implementation-Version"), "Java implementation version"),
                    () -> assertEquals(EXPECTED_GROUP_ID, coordinates.getProperty("groupId"),
                            "packaged Maven groupId"),
                    () -> assertEquals(EXPECTED_ARTIFACT_ID, coordinates.getProperty("artifactId"),
                            "packaged Maven artifactId"),
                    () -> assertEquals(EXPECTED_MAVEN_VERSION, coordinates.getProperty("version"),
                            "packaged Maven version"));
        }
    }

    private static String requiredSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            fail("Missing required system property: " + name);
        }
        return value;
    }
}
