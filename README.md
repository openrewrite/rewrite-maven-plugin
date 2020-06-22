![Logo](https://github.com/openrewrite/rewrite/raw/master/doc/logo-oss.png)
### Eliminate Tech-Debt. At build time.

[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-maven-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/org.openrewrite.maven/rewrite-maven-plugin.svg)](https://mvnrepository.com/artifact/org.openrewrite.maven/rewrite-maven-plugin)

## What is this?

This project provides a Maven plugin that applies [Rewrite](https://github.com/openrewrite/rewrite) plans to automatically apply fixes to your code.

## Getting started

To configure, add the plugin to your POM:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <version>1.1.0</version>
                <configuration>
                    <activeProfiles>spring</activeProfiles>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

The plugin scans all dependencies on the classpath for `META-INF/rewrite/*.yml` files and loads their configuration as well as anything in `~/rewrite.yml`.

This plugin automatically adds `org.openrewrite.plan:rewrite-spring` and `org.openrewrite.plan:rewrite-checkstyle` to the plugin classpath and loads their `spring` and `checkstyle` profiles.

To apply Spring best practices, you must activate the `spring` profile. It is then fully configured.

For Checkstyle auto-fixing to take place, you need to tell the checkstyle profile where to find the checkstyle configuration XML (which should already be a part of your project), as shown in the example below.

## Defining or configuring profiles in the POM

Profiles can be defined directly in the POM, making it easy to share profile configuration across many different repositories via parent POM configuration of the Rewrite plugin.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <version>1.1.0</version>
                <configuration>
                    <activeProfiles>checkstyle</activeProfiles>
                    <profiles>
                        <profile>
                            <name>checkstyle</name>
                            <configure>
                                <property>
                                    <visitor>org.openrewrite.checkstyle.*</visitor>
                                    <key>configFile</key>
                                    <value>${project.basedir}/../checkstyle.xml</value>
                                </property>
                            </configure>
                        </profile>
                    </profiles>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## Applying Rewrite YML configuration

Use the `<configLocation>` property to load a Rewrite YML configuration containing profile and visitor definitions.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <version>1.1.0</version>
                <configuration>
                    <configLocation>rewrite.yml</configLocation>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

`rewrite.yml` is a file in the project that can define Rewrite profile configuration.

```yaml
---
type: beta.openrewrite.org/v1/profile
name: default

configure:
  # Spring Cloud's preferred import ordering scheme
  org.openrewrite.java.OrderImports:
    layout:
      classCountToUseStarImport: 999
      nameCountToUseStarImport: 999
      blocks:
        - import java.*
        - <blank line>
        - import javax.*
        - <blank line>
        - import all other imports
        - <blank line>
        - import org.springframework.*
        - <blank line>
        - import static all other imports
```
