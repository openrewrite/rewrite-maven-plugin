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
                <version>4.1.0</version>
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

## Notes for developing and testing this plugin

This plugin uses the [`Maven Integration Testing Framework Extension`](https://github.com/khmarbaise/maven-it-extension) for tests.

All tests can be run from the command line using:

```sh
./mvnw verify
```

If you're looking for more information on the output from a test, try checking the `target/maven-it/**/*IT/**` directory contents after running the tests. It will contain the project state output, including maven logs, etc. Check the [`Integration Testing Framework Users Guide`](https://khmarbaise.github.io/maven-it-extension/itf-documentation/usersguide/usersguide.html) for information, too. It's good.

### Using this plugin against itself

The `pom.xml` has the most recent officially-released version of the `rewrite-maven-plugin` applied to itself (it's a helpful plugin, why not use it to help develop itself?) Try `./mvnw rewrite:dryRun`.

### Resource guides

- https://carlosvin.github.io/posts/creating-custom-maven-plugin/en/#_dependency_injection
- https://developer.okta.com/blog/2019/09/23/tutorial-build-a-maven-plugin
- https://medium.com/swlh/step-by-step-guide-to-developing-a-custom-maven-plugin-b6e3a0e09966
