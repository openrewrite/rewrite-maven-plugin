package org.openrewrite.maven;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.openrewrite.*;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.maven.tree.MavenModel;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.yaml.YamlParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

public abstract class AbstractRewriteMojo extends AbstractMojo {
    @Parameter(property = "configLocation", defaultValue = "${maven.multiModuleProjectDirectory}/rewrite.yml")
    String configLocation;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "activeRecipes")
    protected Set<String> activeRecipes;

    @Parameter(property = "activeStyles")
    Set<String> activeStyles;

    @Parameter(property = "metricsUri")
    private String metricsUri;

    @Parameter(property = "metricsUsername")
    private String metricsUsername;

    @Parameter(property = "metricsPassword")
    private String metricsPassword;

    protected Environment environment() throws MojoExecutionException {
        Environment.Builder env = Environment
                .builder(project.getProperties())
                .scanClasspath(project.getArtifacts().stream()
                        .map(d -> d.getFile().toPath())
                        .collect(toList()))
                .scanUserHome();

        Path absoluteConfigLocation = Paths.get(configLocation);
        if (!absoluteConfigLocation.isAbsolute()) {
            absoluteConfigLocation = project.getBasedir().toPath().resolve(configLocation);
        }
        File rewriteConfig = absoluteConfigLocation.toFile();

        if (rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                env.load(new YamlResourceLoader(is, rewriteConfig.toURI(), project.getProperties()));
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to load rewrite configuration", e);
            }
        }
        else {
            throw new MojoExecutionException("Unable to load rewrite configuration, because it does not exist: " +
                    rewriteConfig.toString());
        }

        return env.build();
    }

    protected Collection<Change> listChanges() throws MojoExecutionException {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(),
                metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            if (activeRecipes == null || activeRecipes.isEmpty()) {
                return emptyList();
            }

            Environment env = environment();
            Collection<RefactorVisitor<?>> visitors = env.visitors(activeRecipes);

            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> javaSources = new ArrayList<>();
            javaSources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
            javaSources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

            sourceFiles.addAll(JavaParser.fromJavaVersion()
                    .styles(env.styles(activeStyles))
                    .classpath(project.getCompileClasspathElements().stream()
                        .map(Paths::get)
                        .collect(toList()))
                    .logCompilationWarningsAndErrors(false)
                    .meterRegistry(meterRegistry)
                    .build()
                    .parse(javaSources, project.getBasedir().toPath()));

            sourceFiles.addAll(
                    new YamlParser().parse(
                            Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                    .map(Resource::getTargetPath)
                                    .filter(Objects::nonNull)
                                    .filter(it -> it.endsWith(".yml") || it.endsWith(".yaml"))
                                    .map(Paths::get)
                                    .collect(toList()),
                            project.getBasedir().toPath())
            );

            sourceFiles.addAll(
                    new PropertiesParser().parse(
                            Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                    .map(Resource::getTargetPath)
                                    .filter(Objects::nonNull)
                                    .filter(it -> it.endsWith(".properties"))
                                    .map(Paths::get)
                                    .collect(toList()),
                            project.getBasedir().toPath())
            );

            List<Path> allPoms = new ArrayList<>();
            allPoms.add(project.getFile().toPath());

            // children
            if(project.getCollectedProjects() != null) {
                project.getCollectedProjects().stream()
                        .filter(collectedProject -> collectedProject != project)
                        .map(collectedProject -> collectedProject.getFile().toPath())
                        .forEach(allPoms::add);
            }

            // parents
            MavenProject parent = project.getParent();
            while (parent != null && parent.getFile() != null) {
                allPoms.add(parent.getFile().toPath());
                parent = parent.getParent();
            }

            Maven.Pom pomAst = MavenParser.builder()
                    .resolveDependencies(false)
                    .build()
                    .parse(allPoms, project.getBasedir().toPath())
                    .iterator()
                    .next();

            pomAst = pomAst.withModel(pomAst.getModel()
                    .withTransitiveDependenciesByScope(project.getDependencies().stream()
                            .collect(
                                    Collectors.groupingBy(
                                            Dependency::getScope,
                                            Collectors.mapping(dep -> new MavenModel.ModuleVersionId(
                                                            dep.getGroupId(),
                                                            dep.getArtifactId(),
                                                            dep.getClassifier(),
                                                            dep.getVersion(),
                                                            "jar"
                                                    ),
                                                    toSet()
                                            )
                                    )
                            )
                    )
            );

            sourceFiles.add(pomAst);

            return new Refactor().visit(visitors).setMeterRegistry(meterRegistry).fix(sourceFiles);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Dependency resolution required", e);
        }
    }

    protected List<Path> listJavaSources(String sourceDirectory) throws MojoExecutionException {
        File sourceDirectoryFile = new File(sourceDirectory);
        if (!sourceDirectoryFile.exists()) {
            return emptyList();
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        try {
            return Files.walk(sourceRoot)
                    .filter(f -> !Files.isDirectory(f) && f.toFile().getName().endsWith(".java"))
                    .collect(toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list Java source files", e);
        }
    }
}
