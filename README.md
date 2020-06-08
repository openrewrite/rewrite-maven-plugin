![Logo](https://github.com/openrewrite/rewrite/raw/master/doc/logo-oss.png)
### Eliminate Tech-Debt. At build time.

## What is this?

This project provides a Maven plugin that applies [Rewrite](https://github.com/openrewrite/rewrite) checking and fixing tasks as build tasks, one of several possible workflows for propagating change across an organization's source code (along with mass pull request and commit issuance).

The plugin locates profiles and auto-configurable refactoring visitors on the plugin and compilation classpath, in addition
to preloading `rewrite-checkstyle` and `rewrite-spring`.

## Configuring

To configure, add the plugin to your POM:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    
    ...

    <build>
        <plugins>
            <plugin>
                <groupId>org.openrewrite.maven</groupId>
                <artifactId>maven-rewrite-plugin</artifactId>
                <version>VERSION</version>
                <configuration>
                    <metricsUri>LOG</metricsUri>
                    <configLocation>rewrite.yml</configLocation>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```
