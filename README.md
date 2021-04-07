![Logo](https://github.com/openrewrite/rewrite/raw/master/doc/logo-oss.png)
### Eliminate Tech-Debt. At build time.

[![ci](https://github.com/openrewrite/rewrite-maven-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-maven-plugin/actions/workflows/ci.yml)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-maven-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/org.openrewrite.maven/rewrite-maven-plugin.svg)](https://mvnrepository.com/artifact/org.openrewrite.maven/rewrite-maven-plugin)

## What is this?

This project provides a Maven plugin that applies [Rewrite](https://github.com/openrewrite/rewrite) checking and fixing tasks as build tasks, one of several possible workflows for propagating change across an organization's source code.

## Getting started

This `README` may not have the most up-to-date documentation. For the most up-to-date documentation and reference guides, see:

- [Maven Plugin Configuration](https://docs.openrewrite.org/reference/rewrite-maven-plugin)
- [OpenRewrite Quickstart Guide](https://docs.openrewrite.org/getting-started/getting-started)

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
                <version>4.0.0</version>
                <configuration>
                    <activeRecipes>
                        <recipe>org.openrewrite.java.format.AutoFormat</recipe>
                    </activeRecipes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

To get started, try `mvn rewrite:discover`, `mvn rewrite:dryRun`, `mvn rewrite:run`, among other plugin goals.

See the [Maven Plugin Configuration](https://docs.openrewrite.org/reference/rewrite-maven-plugin) documentation for full configuration and usage options.
