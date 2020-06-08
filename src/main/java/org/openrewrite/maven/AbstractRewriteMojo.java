package org.openrewrite.maven;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.openrewrite.Change;
import org.openrewrite.RefactorPlan;
import org.openrewrite.SourceVisitor;
import org.openrewrite.config.ProfileConfiguration;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

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

    @Parameter(property = "profiles", defaultValue = "")
    private List<MavenProfileConfiguration> profiles;

    public static class MavenProfileConfiguration {
        @Parameter(property = "name", defaultValue = "default")
        String name;

        @Parameter(property = "includes")
        private Set<String> include;

        @Parameter(property = "excludes")
        private Set<String> exclude;

        @Parameter(property = "extend")
        private Set<String> extend;

        @Parameter(property = "configure")
        List<MavenProfileProperty> configure;

        public ProfileConfiguration toProfileConfiguration() {
            ProfileConfiguration profile = new ProfileConfiguration();
            profile.setName(name);
            if(include != null) {
                profile.setInclude(include);
            }
            if(exclude != null) {
                profile.setExclude(exclude);
            }
            if(extend != null) {
                profile.setExtend(extend);
            }
            if(configure != null) {
                profile.setConfigure(configure.stream()
                        .collect(toMap(prop -> prop.visitor + "." + prop.key, prop -> prop.value)));
            }
            return profile;
        }
    }

    public static class MavenProfileProperty {
        @Parameter(property = "visitor", required = true)
        String visitor;

        @Parameter(property = "key", required = true)
        String key;

        @Parameter(property = "value", required = true)
        String value;
    }

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

            List<Path> dependencies = project.getArtifacts().stream()
                    .map(d -> d.getFile().toPath())
                    .collect(Collectors.toList());

            List<Path> sources = new ArrayList<>();
            sources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
            sources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

            List<J.CompilationUnit> cus = new JavaParser(dependencies)
                    .setLogCompilationWarningsAndErrors(false)
                    .setMeterRegistry(meterRegistry)
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
