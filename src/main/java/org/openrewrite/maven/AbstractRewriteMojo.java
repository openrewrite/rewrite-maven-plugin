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
import org.openrewrite.config.ProfileConfigurationLoader;
import org.openrewrite.config.YamlProfileConfigurationLoader;
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

public abstract class AbstractRewriteMojo extends AbstractMojo {
    @Parameter(property = "configLocation", defaultValue = "rewrite.yml")
    String configLocation;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "profiles", defaultValue = "default")
    Set<String> profiles;

    @Parameter(property = "excludes")
    Set<String> excludes;

    @Parameter(property = "metricsUri")
    private String metricsUri;

    @Parameter(property = "metricsUsername")
    private String metricsUsername;

    @Parameter(property = "metricsPassword")
    private String metricsPassword;

    protected RefactorPlan plan() throws MojoExecutionException {
        Map<String, Object> baseDirConfigure = new HashMap<>();
        baseDirConfigure.put("*.baseDir", project.getBasedir().toPath());

        ProfileConfiguration baseDir = new ProfileConfiguration();
        baseDir.setName("default");
        baseDir.setConfigure(baseDirConfigure);

        RefactorPlan.Builder plan = RefactorPlan.builder()
                .scanProfiles()
                .loadProfile(baseDir);

        File rewriteConfig = new File(project.getBasedir() + "/" + configLocation);
        if (rewriteConfig.exists()) {
            try (FileInputStream is = new FileInputStream(rewriteConfig)) {
                plan.loadProfiles(new YamlProfileConfigurationLoader(is));
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
            Collection<SourceVisitor<J>> javaVisitors = plan.visitors(J.class, profiles);

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
