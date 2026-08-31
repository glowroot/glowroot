# Glowroot Spring Boot Starter

Marker dependency for discoverability. **Glowroot is a JVM agent**, not a library on the application classpath.

You must still pass `-javaagent` pointing at `org.glowroot:glowroot-agent` (or a Glowroot dist `glowroot.jar`).

Use the same Glowroot version everywhere (property below matches this release line).

## Maven (recommended) — copy agent + Boot `jvmArguments`

Embedded collector (UI on the app, default for local try-out):

```xml
<properties>
  <glowroot.version>0.14.8-beta.5-SNAPSHOT</glowroot.version>
</properties>

<dependencies>
  <!-- marker only; does not attach the agent -->
  <dependency>
    <groupId>org.glowroot</groupId>
    <artifactId>glowroot-spring-boot-starter</artifactId>
    <version>${glowroot.version}</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <artifactId>maven-dependency-plugin</artifactId>
      <executions>
        <execution>
          <id>copy-glowroot-agent</id>
          <!-- early enough for spring-boot:run (does not run package) -->
          <phase>process-resources</phase>
          <goals>
            <goal>copy</goal>
          </goals>
          <configuration>
            <artifactItems>
              <artifactItem>
                <groupId>org.glowroot</groupId>
                <artifactId>glowroot-agent</artifactId>
                <version>${glowroot.version}</version>
                <outputDirectory>${project.build.directory}/glowroot</outputDirectory>
                <destFileName>glowroot-agent.jar</destFileName>
              </artifactItem>
            </artifactItems>
          </configuration>
        </execution>
      </executions>
    </plugin>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
      <configuration>
        <jvmArguments>
          -javaagent:${project.build.directory}/glowroot/glowroot-agent.jar
        </jvmArguments>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Then `mvn spring-boot:run` (agent is copied in `process-resources`).

For an already-built executable jar / production process, set the same agent on the JVM, e.g.:

```text
JAVA_TOOL_OPTIONS=-javaagent:/path/to/glowroot-agent.jar
```

### Central collector

Add collector address (gRPC port, not the UI `:4000`):

```text
-javaagent:.../glowroot-agent.jar
-Dglowroot.collector.address=central-host:8181
```

## Gradle

```gradle
ext.glowrootVersion = '0.14.8-beta.5-SNAPSHOT'

configurations {
  glowrootAgent
}

dependencies {
  implementation "org.glowroot:glowroot-spring-boot-starter:${glowrootVersion}"
  glowrootAgent "org.glowroot:glowroot-agent:${glowrootVersion}"
}

tasks.register('copyGlowrootAgent', Copy) {
  from configurations.glowrootAgent
  into layout.buildDirectory.dir('glowroot')
  rename { 'glowroot-agent.jar' }
}

tasks.named('bootRun') {
  dependsOn('copyGlowrootAgent')
  jvmArgs = [
    "-javaagent:${layout.buildDirectory.get()}/glowroot/glowroot-agent.jar"
  ]
}
```

## External dist path (alternative)

Download a [Glowroot release](https://github.com/glowroot/glowroot/releases) zip and point at `glowroot.jar` inside it:

```text
-javaagent:/opt/glowroot/glowroot.jar
```

## Notes

- Do **not** put `glowroot-agent` on the app classpath as a normal dependency; use `-javaagent` only.
- Spring MVC instrumentation is already inside the agent (`spring-plugin`); this starter does not add weaving.
- Optional embedded UI port: `-Dglowroot.agent.port=4000` (default 4000).
