package org.openrewrite.maven;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.repository.RemoteRepository;
import org.openrewrite.*;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.JavaParser;
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
import static java.util.Collections.singletonList;

public abstract class AbstractRewriteMojo extends AbstractMojo {
    @Parameter(property = "configLocation", defaultValue = "rewrite.yml")
    String configLocation;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "activeProfiles", defaultValue = "default")
    String activeProfiles;

    @Parameter(property = "metricsUri")
    private String metricsUri;

    @Parameter(property = "metricsUsername")
    private String metricsUsername;

    @Parameter(property = "metricsPassword")
    private String metricsPassword;

    @Parameter(property = "profiles")
    private List<MavenProfileConfiguration> profiles;

    protected RefactorPlan plan() throws MojoExecutionException {
        RefactorPlan.Builder plan = RefactorPlan.builder()
                .compileClasspath(project.getArtifacts().stream()
                        .map(d -> d.getFile().toPath())
                        .collect(Collectors.toList()))
                .scanResources()
                .scanUserHome();

        if (profiles != null) {
            profiles.forEach(profile -> plan.loadProfile(profile.toProfileConfiguration()));
        }

        File rewriteConfig = new File(project.getBasedir() + "/" + configLocation);
        if (rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                YamlResourceLoader resourceLoader = new YamlResourceLoader(is);
                plan.loadProfiles(resourceLoader);
                plan.loadVisitors(resourceLoader);
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to load rewrite configuration", e);
            }
        }

        return plan.build();
    }

    protected Collection<Change> listChanges() throws MojoExecutionException {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(),
                metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            RefactorPlan plan = plan();
            Collection<RefactorVisitor<?>> visitors = plan.visitors(
                    Arrays.stream(activeProfiles.split(","))
                            .map(String::trim)
                            .toArray(String[]::new));

            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> javaSources = new ArrayList<>();
            javaSources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
            javaSources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

            sourceFiles.addAll(JavaParser.fromJavaVersion()
                    .classpath(project.getArtifacts().stream()
                            .map(d -> d.getFile().toPath())
                            .collect(Collectors.toList()))
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
                                    .collect(Collectors.toList()),
                            project.getBasedir().toPath())
            );

            sourceFiles.addAll(
                    new PropertiesParser().parse(
                            Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                    .map(Resource::getTargetPath)
                                    .filter(Objects::nonNull)
                                    .filter(it -> it.endsWith(".properties"))
                                    .map(Paths::get)
                                    .collect(Collectors.toList()),
                            project.getBasedir().toPath())
            );

            File localRepo = new File(new File(project.getBuild().getOutputDirectory(), "rewrite"), ".m2");
            if (localRepo.mkdirs()) {
                sourceFiles.addAll(
                        MavenParser.builder()
                                .localRepository(localRepo)
                                .remoteRepositories(project.getRepositories().stream()
                                        .map(repo -> new RemoteRepository.Builder("central", "default",
                                                "https://repo1.maven.org/maven2/").build())
                                        .collect(Collectors.toList())
                                )
                                .build().parse(singletonList(project.getFile().toPath()), project.getBasedir().toPath())
                );
            }

            return new Refactor().visit(visitors).fix(sourceFiles);
        }
    }

    private List<Path> listJavaSources(String sourceDirectory) throws MojoExecutionException {
        File sourceDirectoryFile = new File(sourceDirectory);
        if (!sourceDirectoryFile.exists()) {
            return emptyList();
        }

        Path sourceRoot = sourceDirectoryFile.toPath();
        try {
            return Files.walk(sourceRoot)
                    .filter(f -> !Files.isDirectory(f) && f.toFile().getName().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list Java source files", e);
        }
    }
}
