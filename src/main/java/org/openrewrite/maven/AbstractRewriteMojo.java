package org.openrewrite.maven;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.openrewrite.Change;
import org.openrewrite.RefactorPlan;
import org.openrewrite.SourceVisitor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

public abstract class AbstractRewriteMojo extends AbstractMojo {
    @Parameter(property = "configLocation", defaultValue = "rewrite.yml")
    String configLocation;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "activeProfiles", defaultValue = "default")
    Set<String> activeProfiles;

    @Parameter(property = "excludes")
    Set<String> excludes;

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

        if(profiles != null) {
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

    protected List<Change<J.CompilationUnit>> listChanges() throws MojoExecutionException {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(),
                metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            RefactorPlan plan = plan();
            Collection<SourceVisitor<J>> javaVisitors = plan.visitors(J.class, activeProfiles);

            plan.configure(AddImport.orderImports, "default");

            List<Path> dependencies = project.getArtifacts().stream()
                    .map(d -> d.getFile().toPath())
                    .collect(Collectors.toList());

            List<Path> sources = new ArrayList<>();
            sources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
            sources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

            JavaParser.Builder<? extends JavaParser, ?> javaParser;
            try {
                if (System.getProperty("java.version").startsWith("1.8")) {
                    javaParser = (JavaParser.Builder<? extends JavaParser, ?>) Class
                            .forName("org.openrewrite.java.Java8Parser")
                            .getDeclaredMethod("builder")
                            .invoke(null);
                } else {
                    javaParser = (JavaParser.Builder<? extends JavaParser, ?>) Class
                            .forName("org.openrewrite.java.Java11Parser")
                            .getDeclaredMethod("builder")
                            .invoke(null);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create a Java parser instance. " +
                        "`rewrite-java-8` or `rewrite-java-11` must be on the classpath.");
            }

            List<J.CompilationUnit> cus = javaParser
                    .classpath(dependencies)
                    .logCompilationWarningsAndErrors(false)
                    .meterRegistry(meterRegistry)
                    .build()
                    .parse(sources, project.getBasedir().toPath());

            return cus.stream()
                    .map(cu -> cu.refactor()
                            .visit(javaVisitors)
                            .setMeterRegistry(meterRegistry)
                            .fix())
                    .filter(change -> !change.getRulesThatMadeChanges().isEmpty())
                    .collect(toList());
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
