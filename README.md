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
                <version>2.0.0</version>
                <configuration>
                    <activeRecipes>org.openrewrite.spring</activeRecipes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

The plugin scans all dependencies on the classpath for `META-INF/rewrite/*.yml` files and loads their configuration as well as anything in `~/rewrite.yml`.

This plugin automatically adds `org.openrewrite.plan:rewrite-spring` to the plugin classpath and loads the `org.openrewrite.spring` recipe.

To apply Spring best practices, you must activate the `org.openrewrite.spring` recipe. It is then fully configured.

## Defining or configuring recipes in the POM

Recipes can be defined directly in the POM, making it easy to share recipe configuration across many different repositories via parent POM configuration of the Rewrite plugin.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <version>2.0.0</version>
                <configuration>
                    <activeRecipes>org.openrewrite.checkstyle</activeRecipes>
                    <recipes>
                        <recipe>
                            <name>org.openrewrite.checkstyle</name>
                            <configure>
                                <property>
                                    <visitor>org.openrewrite.checkstyle.*</visitor>
                                    <key>configFile</key>
                                    <value>${project.basedir}/../checkstyle.xml</value>
                                </property>
                            </configure>
                        </recipe>
                    </recipes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## Applying Rewrite YML configuration

Use the `<configLocation>` property to load a Rewrite YML configuration containing recipe and visitor definitions.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <version>2.0.0</version>
                <configuration>
                    <configLocation>rewrite.yml</configLocation>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

`rewrite.yml` is a file in the project that can define Rewrite recipe configuration.

```yaml
---
type: specs.openrewrite.org/v1beta/recipe
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
