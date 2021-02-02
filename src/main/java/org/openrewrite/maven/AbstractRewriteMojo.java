package org.openrewrite.maven;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.openrewrite.*;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

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

        return env.build();
    }

    protected ChangesContainer listChanges() throws MojoExecutionException {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(),
                metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            // This property is set by Maven, apparently for both multi and single module builds
            Object maybeMultiModuleDir = System.getProperties().get("maven.multiModuleProjectDirectory");
            Path baseDir;
            if (maybeMultiModuleDir instanceof String) {
                baseDir = Paths.get((String) maybeMultiModuleDir);
            } else {
                // This path should only be taken by tests using AbstractMojoTestCase
                baseDir = project.getBasedir().toPath();
            }

            if (activeRecipes == null || activeRecipes.isEmpty()) {
                return new ChangesContainer(baseDir, emptyList());
            }

            Environment env = environment();
            List<RefactorVisitor<?>> visitors = new ArrayList<>(env.visitors(activeRecipes));
            if(visitors.size() == 0) {
                getLog().warn("Could not find any Rewrite visitors matching active recipe(s): " + String.join(", ", activeRecipes) + ". " +
                        "Double check that you have taken a dependency on the jar containing these recipes.");
                return new ChangesContainer(baseDir, emptyList());
            }

            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> javaSources = new ArrayList<>();
            javaSources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
            javaSources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

            sourceFiles.addAll(JavaParser.fromJavaVersion()
                    .styles(env.styles(activeStyles))
                    .classpath(
                            Stream.concat(
                                    project.getCompileClasspathElements().stream(),
                                    project.getTestClasspathElements().stream()
                            )
                                    .distinct()
                                    .map(Paths::get)
                                    .collect(toList())
                    )
                    .logCompilationWarningsAndErrors(false)
                    .meterRegistry(meterRegistry)
                    .build()
                    .parse(javaSources, baseDir));

            sourceFiles.addAll(
                    new YamlParser().parse(
                            Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                    .map(Resource::getTargetPath)
                                    .filter(Objects::nonNull)
                                    .filter(it -> it.endsWith(".yml") || it.endsWith(".yaml"))
                                    .map(Paths::get)
                                    .collect(toList()),
                            baseDir)
            );

            sourceFiles.addAll(
                    new PropertiesParser().parse(
                            Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                    .map(Resource::getTargetPath)
                                    .filter(Objects::nonNull)
                                    .filter(it -> it.endsWith(".properties"))
                                    .map(Paths::get)
                                    .collect(toList()),
                            baseDir)
            );

            sourceFiles.addAll(new XmlParser().parse(
                    Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                            .map(Resource::getTargetPath)
                            .filter(Objects::nonNull)
                            .filter(it -> it.endsWith(".xml"))
                            .map(Paths::get)
                            .collect(toList()),
                    baseDir)
            );

            List<Path> allPoms = new ArrayList<>();
            allPoms.add(project.getFile().toPath());

            // children
            if (project.getCollectedProjects() != null) {
                project.getCollectedProjects().stream()
                        .filter(collectedProject -> collectedProject != project)
                        .map(collectedProject -> collectedProject.getFile().toPath())
                        .forEach(allPoms::add);
            }

            MavenProject parent = project.getParent();
            while (parent != null && parent.getFile() != null) {
                allPoms.add(parent.getFile().toPath());
                parent = parent.getParent();
            }

            try {
                Path mavenSettings = Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml");

                MavenParser.Builder mavenParserBuilder = MavenParser.builder()
                        .resolveOptional(false)
                        .onError(throwable -> {
                            getLog().warn(throwable.getMessage());
                            getLog().debug(throwable);
                        })
                        .mavenConfig(baseDir.resolve(".mvn/maven.config"));

                if(System.getProperty("org.openrewrite.maven.continueOnError") != null) {
                    mavenParserBuilder.continueOnError(true);
                }

                if (mavenSettings.toFile().exists()) {
                    mavenParserBuilder = mavenParserBuilder.mavenSettings(new Parser.Input(mavenSettings,
                            () -> {
                                try {
                                    return Files.newInputStream(mavenSettings);
                                } catch (IOException e) {
                                    getLog().warn("Unable to load Maven settings from user home directory. Skipping.", e);
                                    return null;
                                }
                            }));
                }

                Maven pomAst = mavenParserBuilder
                        .build()
                        .parse(allPoms, baseDir)
                        .iterator()
                        .next();

                sourceFiles.add(pomAst);
            } catch (Throwable t) {
                // TODO we aren't yet confident enough in this to not squash exceptions
                getLog().warn("Unable to parse Maven AST", t);
            }

            Collection<Change> changes = new Refactor().visit(visitors)
                    .setMeterRegistry(meterRegistry)
                    .fix(sourceFiles);

            return new ChangesContainer(baseDir, changes);
        } catch (
                DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Dependency resolution required", e);
        }
    }

    public static class ChangesContainer {
        final Path projectRoot;
        final List<Change> generated = new ArrayList<>();
        final List<Change> deleted = new ArrayList<>();
        final List<Change> moved = new ArrayList<>();
        final List<Change> refactoredInPlace = new ArrayList<>();

        public ChangesContainer(Path projectRoot, Collection<Change> changes) {
            this.projectRoot = projectRoot;
            for (Change change : changes) {
                if (change.getOriginal() == null && change.getFixed() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (change.getOriginal() == null && change.getFixed() != null) {
                    generated.add(change);
                } else if (change.getOriginal() != null && change.getFixed() == null) {
                    deleted.add(change);
                } else if (change.getOriginal() != null && !change.getOriginal().getSourcePath().equals(change.getFixed().getSourcePath())) {
                    moved.add(change);
                } else {
                    refactoredInPlace.add(change);
                }
            }
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
        }

        public Stream<Change> stream() {
            return Stream.concat(
                    Stream.concat(generated.stream(), deleted.stream()),
                    Stream.concat(moved.stream(), refactoredInPlace.stream())
            );
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

    protected void logVisitorsThatMadeChanges(Change change) {
        for (String visitor : change.getVisitorsThatMadeChanges()) {
            getLog().warn("  " + visitor);
        }
    }
}
