package org.openrewrite.maven;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.style.NamedStyles;
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
    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "configLocation", defaultValue = "${maven.multiModuleProjectDirectory}/rewrite.yml")
    String configLocation;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(property = "activeRecipes")
    protected Set<String> activeRecipes = Collections.emptySet();

    @Parameter(property = "activeStyles")
    protected Set<String> activeStyles = Collections.emptySet();

    @Nullable
    @Parameter(property = "metricsUri")
    private String metricsUri;

    @Nullable
    @Parameter(property = "metricsUsername")
    private String metricsUsername;

    @Nullable
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


    /**
     * Maven dependency resolution has a few bugs that lead to the log filling up with (recoverable) errors.
     * While we work on fixing those issues, set this to 'true' during maven parsing to avoid log spam
     */
    protected boolean suppressWarnings = false;
    protected ExecutionContext executionContext() {
        return new InMemoryExecutionContext(t -> {
            if(!suppressWarnings) {
                getLog().warn(t.getMessage());
            }
            getLog().debug(t);
        });
    }

    protected Parser.Listener listener() {
        return new Parser.Listener() {
            @Override
            public void onError(String message) {
                getLog().error(message);
            }

            @Override
            public void onError(String message, Throwable t) {
                getLog().error(message, t);
            }
        };
    }

    protected Maven parseMaven(Path baseDir, ExecutionContext ctx) {
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

        MavenParser.Builder mavenParserBuilder = MavenParser.builder()
                .resolveOptional(false)
                .mavenConfig(baseDir.resolve(".mvn/maven.config"));

        Path mavenSettings = Paths.get(System.getProperty("user.home")).resolve(".m2/settings.xml");
        if (mavenSettings.toFile().exists()) {
            MavenSettings settings = MavenSettings.parse(new Parser.Input(mavenSettings,
                            () -> {
                                try {
                                    return Files.newInputStream(mavenSettings);
                                } catch (IOException e) {
                                    getLog().warn("Unable to load Maven settings from user home directory. Skipping.", e);
                                    return null;
                                }
                            }),
                    ctx);
            if (settings != null && settings.getActiveProfiles() != null) {
                mavenParserBuilder.activeProfiles(settings.getActiveProfiles().getActiveProfiles().toArray(new String[]{}));
            }
        }

        try {
            // suppressing warnings down to debug log level is temporary while we work out the kinks in maven dependency resolution
            suppressWarnings = true;
            return mavenParserBuilder
                    .build()
                    .parse(allPoms, baseDir, ctx)
                    .iterator()
                    .next();
        } finally {
            suppressWarnings = false;
        }
    }

    protected Path getBaseDir() {
        // This property is set by Maven, apparently for both multi and single module builds
        Object maybeMultiModuleDir = System.getProperties().get("maven.multiModuleProjectDirectory");
        Path baseDir;
        try {
            if (maybeMultiModuleDir instanceof String) {
                return Paths.get((String) maybeMultiModuleDir).toRealPath();
            } else {
                // This path should only be taken by tests using AbstractMojoTestCase
                return project.getBasedir().toPath().toRealPath();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected ResultsContainer listResults() throws MojoExecutionException {
        try (MeterRegistryProvider meterRegistryProvider = new MeterRegistryProvider(getLog(),
                metricsUri, metricsUsername, metricsPassword)) {
            MeterRegistry meterRegistry = meterRegistryProvider.registry();

            Path baseDir = getBaseDir();
            if (activeRecipes.isEmpty()) {
                return new ResultsContainer(baseDir, emptyList());
            }

            Environment env = environment();

            List<NamedStyles> styles;
            styles = env.activateStyles(activeStyles);
            Recipe recipe = env.activateRecipes(activeRecipes);

            List<SourceFile> sourceFiles = new ArrayList<>();
            List<Path> javaSources = new ArrayList<>();
            javaSources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
            javaSources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

            ExecutionContext ctx = executionContext();
            Parser.Listener listener = listener();

            sourceFiles.addAll(JavaParser.fromJavaVersion()
                    .styles(styles)
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
                    .build()
                    .parse(javaSources, baseDir, ctx));

            sourceFiles.addAll(
                    YamlParser.builder()
                            .doOnParse(listener)
                            .build()
                            .parse(
                                    Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                            .map(Resource::getTargetPath)
                                            .filter(Objects::nonNull)
                                            .filter(it -> it.endsWith(".yml") || it.endsWith(".yaml"))
                                            .map(Paths::get)
                                            .collect(toList()),
                                    baseDir,
                                    ctx)
            );

            sourceFiles.addAll(
                    PropertiesParser.builder()
                            .doOnParse(listener)
                            .build()
                            .parse(
                                    Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                            .map(Resource::getTargetPath)
                                            .filter(Objects::nonNull)
                                            .filter(it -> it.endsWith(".properties"))
                                            .map(Paths::get)
                                            .collect(toList()),
                                    baseDir,
                                    ctx)
            );

            sourceFiles.addAll(
                    XmlParser.builder()
                            .doOnParse(listener)
                            .build().parse(
                            Stream.concat(project.getBuild().getResources().stream(), project.getBuild().getTestResources().stream())
                                    .map(Resource::getTargetPath)
                                    .filter(Objects::nonNull)
                                    .filter(it -> it.endsWith(".xml"))
                                    .map(Paths::get)
                                    .collect(toList()),
                            baseDir,
                            ctx)
            );

            Maven pomAst = parseMaven(baseDir, ctx);
            sourceFiles.add(pomAst);

            List<Result> results = recipe.run(sourceFiles, ctx);

            return new ResultsContainer(baseDir, results);
        } catch (
                DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Dependency resolution required", e);
        }
    }

    public static class ResultsContainer {
        final Path projectRoot;
        final List<Result> generated = new ArrayList<>();
        final List<Result> deleted = new ArrayList<>();
        final List<Result> moved = new ArrayList<>();
        final List<Result> refactoredInPlace = new ArrayList<>();

        public ResultsContainer(Path projectRoot, Collection<Result> results) {
            this.projectRoot = projectRoot;
            for (Result result : results) {
                if (result.getBefore() == null && result.getAfter() == null) {
                    // This situation shouldn't happen / makes no sense, log and skip
                    continue;
                }
                if (result.getBefore() == null && result.getAfter() != null) {
                    generated.add(result);
                } else if (result.getBefore() != null && result.getAfter() == null) {
                    deleted.add(result);
                } else if (result.getBefore() != null && !result.getBefore().getSourcePath().equals(result.getAfter().getSourcePath())) {
                    moved.add(result);
                } else {
                    refactoredInPlace.add(result);
                }
            }
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public boolean isNotEmpty() {
            return !generated.isEmpty() || !deleted.isEmpty() || !moved.isEmpty() || !refactoredInPlace.isEmpty();
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
                    .map(it -> {
                        try {
                            return it.toRealPath();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list Java source files", e);
        }
    }

    protected void logRecipesThatMadeChanges(Result result) {
        for (Recipe recipe : result.getRecipesThatMadeChanges()) {
            getLog().warn("  " + recipe.getName());
        }
    }
}
