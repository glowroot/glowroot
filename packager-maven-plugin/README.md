Glowroot Package Plugin
=========

## Introduction
THis maven plugin can be used to create a smaller, configurable distribution of the glowroot agent. It can be used to override configuration files (i.e. `glowroot.logback.xml`), plugin configuration properties and additional add plugins or services.

## Exclusions

``` xml
<configuration>
    <excludes>
        <exclude>netty:*</exclude>
        <exclude>h2:*</exclude>
        ...
```

With the `excludes` property it is possible to exclude packages. Because glowroot is shadowing the packages, the plugin excludes everything based on the shadow package name. When using `netty.*` all package starting with `org.glowroot.agent.shaded.netty` is excluded when building the package. It is also possible to exclude an exact classname, i.e. `io:netty:channel:ChannelOutboundHandler`.

## Overrides

``` xml
<overrides>
    <override>glowroot.logback.xml</override>
    ...
</overrides>

```

With `overrides` property it is possible to override certain files available in the `glowroot-agent` package. I.e. the `glowroot.logback.xml`. The file must be available in the `src/main/resource/<dir>/<filename>` directory.


## Plugins
``` xml
<plugins>
    <plugin>
        <id>spring</id>
        <properties>
            <property>
                <name>useAltTransactionNaming</name>
                <defaultValue>true</defaultValue>
                ...
```

With the `plugins` property it is possible to override certain plugin configuration properties. The other properties and plugins that are not mentioned are kept intact.

## Example configuration

The following pom.xml creates a smaller glowroot package, without the embedded netty server, ui package and storage package.

``` xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.glowroot</groupId>
    <artifactId>glowroot-example-dist</artifactId>
    <version>0.0.1-SNAPSHOT</version>

    <name>glowroot-customized-dist</name>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <glowroot.version>0.9-SNAPSHOT</glowroot.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent</artifactId>
            <version>${glowroot.version}</version>
            <exclusions>
                <exclusion>
                    <groupId>org.glowroot</groupId>
                    <artifactId>glowroot-storage</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.glowroot</groupId>
                    <artifactId>glowroot-ui</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-cassandra-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-executor-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-hibernate-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-http-client-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-jdbc-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-jms-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-jsf-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-jsp-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-logger-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-quartz-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-servlet-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-struts-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.glowroot</groupId>
            <artifactId>glowroot-agent-spring-plugin</artifactId>
            <version>${glowroot.version}</version>
        </dependency>
        <!--
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>external-collector</artifactId>
            <version>1.2.3</version>
        </dependency>
      -->
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.glowroot</groupId>
                <artifactId>packager-maven-plugin</artifactId>
                <version>0.9-SNAPSHOT</version>
                <configuration>
                    <finalName>glowroot-customized-dist-${project.version}</finalName>
                    <excludes>
                        <!-- exclude packages -->
                        <exclude>netty:*</exclude>
                        <exclude>h2:*</exclude>
                        <exclude>jcraft:*</exclude>
                        <exclude>glowroot:ui:*</exclude>
                        <exclude>glowroot:storage:*</exclude>
                        <exclude>sun:mail:*</exclude>
                        <exclude>ning:*</exclude>
                    </excludes>
                    <overrides>
                        <!-- override file, must be available in src/main/resources/glowroot.logback.xml -->
                        <override>glowroot.logback.xml</override>
                    </overrides>
                    <plugins>
                        <plugin>
                            <id>spring</id>
                            <properties>
                                <property>
                                    <name>useAltTransactionNaming</name>
                                    <defaultValue>true</defaultValue>
                                </property>
                            </properties>
                        </plugin>
                    </plugins>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>package</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <!-- maven is expecting a jar plugin when running package, but not needed -->
            <plugin>
                <artifactId>maven-jar-plugin</artifactId>
                <version>2.6</version>
                <executions>
                    <execution>
                        <id>default-jar</id>
                        <phase>never</phase>
                        <configuration>
                            <finalName>unwanted</finalName>
                            <classifier>unwanted</classifier>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```
