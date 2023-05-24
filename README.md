![Logo](https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss.png)
### Eliminate Tech-Debt. At build time.

[![ci](https://github.com/openrewrite/rewrite-maven-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-maven-plugin/actions/workflows/ci.yml)
[![Apache 2.0](https://img.shields.io/github/license/openrewrite/rewrite-maven-plugin.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/org.openrewrite.maven/rewrite-maven-plugin.svg)](https://mvnrepository.com/artifact/org.openrewrite.maven/rewrite-maven-plugin)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)

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
                <version><!-- latest version here --></version>
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

If wanting to leverage recipes from other dependencies:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <version><!-- latest version here --></version>
                <configuration>
                    <activeRecipes>
                        <recipe>org.openrewrite.java.testing.junit5.JUnit5BestPractices</recipe>
                        <recipe>org.openrewrite.github.ActionsSetupJavaAdoptOpenJDKToTemurin</recipe>
                    </activeRecipes>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>rewrite-testing-frameworks</artifactId>
                        <version><!-- latest dependency version here --></version>
                    </dependency>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>rewrite-github-actions</artifactId>
                        <version><!-- latest dependency version here --></version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
    </build>
</project>
```

To get started, try `mvn rewrite:help`, `mvn rewrite:discover`, `mvn rewrite:dryRun`, `mvn rewrite:run`, among other plugin goals.

See the [Maven Plugin Configuration](https://docs.openrewrite.org/reference/rewrite-maven-plugin) documentation for full configuration and usage options.

### Snapshots

To use the latest `-SNAPSHOT` version, add a `<pluginRepositories>` entry for OSSRH snapshots. For example:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>rewrite-maven-plugin</artifactId>
                <!-- Use whichever version is latest at the time of reading. This number is a placeholder. -->
                <version>4.17.0-SNAPSHOT</version>
                <configuration>
                    <activeRecipes>
                        <recipe>org.openrewrite.java.logging.slf4j.Log4j2ToSlf4j</recipe>
                    </activeRecipes>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.openrewrite.recipe</groupId>
                        <artifactId>rewrite-testing-frameworks</artifactId>
                        <!-- Use whichever version is latest at the time of reading. This number is a placeholder. -->
                        <version>1.1.0-SNAPSHOT</version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
    </build>

    <pluginRepositories>
        <pluginRepository>
            <id>ossrh-snapshots</id>
            <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        </pluginRepository>
    </pluginRepositories>

</project>
```

## Notes for developing and testing this plugin

This plugin uses the [`Maven Integration Testing Framework Extension`](https://github.com/khmarbaise/maven-it-extension) for tests.

All tests can be run from the command line using:

```sh
./mvnw verify
```

If you're looking for more information on the output from a test, try checking the `target/maven-it/**/*IT/**` directory contents after running the tests. It will contain the project state output, including maven logs, etc. Check the [`Integration Testing Framework Users Guide`](https://khmarbaise.github.io/maven-it-extension/itf-documentation/usersguide/usersguide.html) for information, too. It's good.

### Using this plugin against itself

The `pom.xml` file contains a `bootstrap` profile to use the `rewrite-maven-plugin` against itself (it's a helpful plugin, why not use it to help develop itself?).

```sh
./mvnw -Pbootstrap rewrite:dryRun
```

## Contributing

We appreciate all types of contributions. See the [contributing guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md) for detailed instructions on how to get started.

### Resource guides

- https://blog.soebes.io/posts/2020/08/2020-08-17-itf-part-i/
- https://carlosvin.github.io/posts/creating-custom-maven-plugin/en/#_dependency_injection
- https://developer.okta.com/blog/2019/09/23/tutorial-build-a-maven-plugin
- https://medium.com/swlh/step-by-step-guide-to-developing-a-custom-maven-plugin-b6e3a0e09966
