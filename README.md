<p align="center">
  <a href="https://docs.openrewrite.org">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-dark.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg">
      <img alt="OpenRewrite Logo" src="https://github.com/openrewrite/rewrite/raw/main/doc/logo-oss-light.svg" width='600px'>
    </picture>
  </a>
</p>

<div align="center">
  <h1>rewrite-maven-plugin</h1>
</div>

<div align="center">

<!-- Keep the gap above this line, otherwise they won't render correctly! -->
[![ci](https://github.com/openrewrite/rewrite-maven-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/openrewrite/rewrite-maven-plugin/actions/workflows/ci.yml)
[![Contributing Guide](https://img.shields.io/badge/Contributing-Guide-informational)](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md)
</div>

## What is this?

This project provides a Maven plugin that applies [Rewrite](https://github.com/openrewrite/rewrite) checking and fixing tasks as build tasks, one of several possible workflows for propagating change across an organization's source code.

## Getting started

This `README` may not have the most up-to-date documentation. For the most up-to-date documentation and reference guides, see:

- [Auto-generated maven plugin documentation](https://openrewrite.github.io/rewrite-maven-plugin/plugin-info.html)
- [Maven Plugin Configuration](https://docs.openrewrite.org/reference/rewrite-maven-plugin)
- [OpenRewrite Quickstart Guide](https://docs.openrewrite.org/running-recipes/getting-started)

### Artifact repository

OpenRewrite artifacts are published to the [Code Genome Project](https://artifacts.codegenomeproject.org/maven) rather than Maven Central. Versions released to Maven Central before the switch remain available there, but new releases are published only to the Code Genome Project.

Reads require authentication. Sign in to the Code Genome Project and create a download token, then add a server to your `~/.m2/settings.xml` using the email or username you signed in with, plus that token as the password. See the [OpenRewrite Quickstart Guide](https://docs.openrewrite.org/running-recipes/getting-started) for details.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings>
    <servers>
        <server>
            <id>codegenome</id>
            <username>USERNAME</username>
            <password>TOKEN</password>
        </server>
    </servers>
</settings>
```

Then declare the repository in your POM, under both `<repositories>` and `<pluginRepositories>`, using the same `codegenome` id as the server above:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    ...
    <repositories>
        <repository>
            <id>codegenome</id>
            <url>https://artifacts.codegenomeproject.org/maven</url>
            <snapshots>
                <enabled>true</enabled>
            </snapshots>
        </repository>
    </repositories>

    <pluginRepositories>
        <pluginRepository>
            <id>codegenome</id>
            <url>https://artifacts.codegenomeproject.org/maven</url>
            <snapshots>
                <enabled>true</enabled>
            </snapshots>
        </pluginRepository>
    </pluginRepositories>

</project>
```

Both entries are needed, as they cover different resolution paths. `<pluginRepositories>` resolves the plugin itself and any recipe modules declared in its `<dependencies>`, while `<repositories>` resolves `rewrite.recipeArtifactCoordinates` and any OpenRewrite artifacts in your project's own dependency tree. Snapshots are enabled on both because releases and snapshots are served from this one repository.

If your `settings.xml` defines a catch-all mirror, exclude the Code Genome Project from it so that the `codegenome` credentials still apply; otherwise configure the mirror itself to proxy the repository:

```xml
<mirror>
    <id>internal-repository</id>
    <url>https://repo.example.com/maven2</url>
    <mirrorOf>*,!codegenome</mirrorOf>
</mirror>
```

### Plugin configuration

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

Snapshots are served from the same Code Genome Project repository as releases, so the configuration above is all that is needed to resolve them. Set the plugin version, and the version of any recipe modules, to the `-SNAPSHOT` you want:

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
</project>
```

## Notes for developing and testing this plugin

This plugin uses the [`Maven Integration Testing Framework Extension`](https://github.com/khmarbaise/maven-it-extension) for tests.

All tests can be run from the command line using:

```sh
./mvnw verify
```

If you're looking for more information on the output from a test, try checking the `target/maven-it/**/*IT/**` directory contents after running the tests. It will contain the project state output, including maven logs, etc. Check the [`Integration Testing Framework Users Guide`](https://khmarbaise.github.io/maven-it-extension/itf-documentation/usersguide/usersguide.html) for information, too. It's good.

## Contributing

We appreciate all types of contributions. See the [contributing guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md) for detailed instructions on how to get started.

### Resource guides

- https://blog.soebes.io/posts/2020/08/2020-08-17-itf-part-i/
- https://carlosvin.github.io/posts/creating-custom-maven-plugin/en/#_dependency_injection
- https://developer.okta.com/blog/2019/09/23/tutorial-build-a-maven-plugin
- https://medium.com/swlh/step-by-step-guide-to-developing-a-custom-maven-plugin-b6e3a0e09966
