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
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiPredicate;
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

    @Parameter(property = "pomCacheEnabled", defaultValue = "true")
    private boolean pomCacheEnabled;

    @Nullable
    @Parameter(property = "pomCacheDirectory")
    private String pomCacheDirectory;

    /**
     * Whether to throw an exception if an activeRecipe fails configuration validation.
     * This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured.
     * <p>
     * For the time, this default is "false" to prevent one improperly recipe from failing the build.
     * In the future, this default may be changed to "true" to be more restrictive.
     */
    @Parameter(property = "failOnInvalidActiveRecipes", defaultValue = "false")
    private boolean failOnInvalidActiveRecipes;

    /**
     * The prefix used to left-pad log messages, multiplied per "level" of log message.
     */
    private static final String LOG_INDENT_INCREMENT = "    ";

    private static final String RECIPE_NOT_FOUND_EXCEPTION_MSG = "Could not find recipe '%s' among available recipes";

    protected Environment environment() throws MojoExecutionException {
        Environment.Builder env = Environment
                .builder(project.getProperties())
                .scanRuntimeClasspath()
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
            if (!suppressWarnings) {
                getLog().warn(t.getMessage());
            }
            getLog().debug(t);
        });
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
                .mavenConfig(baseDir.resolve(".mvn/maven.config"));

        if (pomCacheEnabled) {
            try {
                if (pomCacheDirectory == null) {
                    //Default directory is "~/.rewrite/cache/pom"
                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(System.getProperty("user.home"), ".rewrite", "cache", "pom").toFile()));
                } else {

                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(pomCacheDirectory).toFile()));
                }
            } catch (Exception e) {
                getLog().warn("Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache");
                getLog().debug(e);
                mavenParserBuilder.cache(new InMemoryMavenPomCache());
            }
        }

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
            getLog().info(String.format("Using active recipe(s) %s", activeRecipes));
            getLog().info(String.format("Using active styles(s) %s", activeStyles));
            if (activeRecipes.isEmpty()) {
                return new ResultsContainer(baseDir, emptyList());
            }

            Environment env = environment();

            List<NamedStyles> styles;
            styles = env.activateStyles(activeStyles);
            Recipe recipe = env.activateRecipes(activeRecipes);

            getLog().info("Validating active recipes...");
            Collection<Validated> validated = recipe.validateAll();
            List<Validated.Invalid> failedValidations = validated.stream().map(Validated::failures)
                    .flatMap(Collection::stream).collect(toList());
            if (!failedValidations.isEmpty()) {
                failedValidations.forEach(failedValidation -> getLog().error(
                        "Recipe validation error in " + failedValidation.getProperty() + ": " +
                                failedValidation.getMessage(), failedValidation.getException()));
                if (failOnInvalidActiveRecipes) {
                    throw new MojoExecutionException("Recipe validation errors detected as part of one or more activeRecipe(s). Please check error logs.");
                } else {
                    getLog().error("Recipe validation errors detected as part of one or more activeRecipe(s). Execution will continue regardless.");
                }
            }

            List<Path> javaSources = new ArrayList<>();
            javaSources.addAll(listJavaSources(project.getBuild().getSourceDirectory()));
            javaSources.addAll(listJavaSources(project.getBuild().getTestSourceDirectory()));

            ExecutionContext ctx = executionContext();

            getLog().info("Parsing Java files...");
            List<SourceFile> sourceFiles = new ArrayList<>(JavaParser.fromJavaVersion()
                    .relaxedClassTypeMatching(true)
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

            Set<Path> resources = new HashSet<>();
            for (Resource resource : project.getBuild().getResources()) {
                addToResources(ctx, resources, resource);
            }
            for (Resource resource : project.getBuild().getTestResources()) {
                addToResources(ctx, resources, resource);
            }

            getLog().info("Parsing YAML files...");
            sourceFiles.addAll(
                    new YamlParser()
                            .parse(resources.stream()
                                            .filter(it -> it.getFileName().toString().endsWith(".yml") || it.getFileName().toString().endsWith(".yaml"))
                                            .collect(toList()),
                                    baseDir,
                                    ctx
                            )
            );

            getLog().info("Parsing properties files...");
            sourceFiles.addAll(
                    new PropertiesParser()
                            .parse(resources.stream()
                                            .filter(it -> it.getFileName().toString().endsWith(".properties"))
                                            .collect(toList()),
                                    baseDir,
                                    ctx
                            )
            );

            getLog().info("Parsing XML files...");
            sourceFiles.addAll(
                    new XmlParser()
                            .parse(resources.stream()
                                            .filter(it -> it.getFileName().toString().endsWith(".xml"))
                                            .collect(toList()),
                                    baseDir,
                                    ctx
                            )
            );

            getLog().info("Parsing POM...");
            Maven pomAst = parseMaven(baseDir, ctx);
            sourceFiles.add(pomAst);

            getLog().info("Running recipe(s)...");
            List<Result> results = recipe.run(sourceFiles, ctx);

            return new ResultsContainer(baseDir, results);
        } catch (
                DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Dependency resolution required", e);
        }
    }

    private void addToResources(ExecutionContext ctx, Set<Path> resources, Resource resource) {
        File file = new File(resource.getDirectory());
        if (file.exists()) {
            BiPredicate<Path, BasicFileAttributes> predicate = (p, bfa) ->
                    bfa.isRegularFile() && Stream.of("yml", "yaml", "properties", "xml").anyMatch(type -> p.getFileName().toString().endsWith(type));
            try {
                Files.find(file.toPath(), 999, predicate).forEach(resources::add);
            } catch (IOException e) {
                ctx.getOnError().accept(e);
            }
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

    protected static List<Path> listJavaSources(String sourceDirectory) throws MojoExecutionException {
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
            getLog().warn(indent(1, recipe.getName()));
        }
    }

    protected static StringBuilder indent(int indent, CharSequence content) {
        StringBuilder prefix = repeat(indent, LOG_INDENT_INCREMENT);
        return prefix.append(content);
    }

    private static StringBuilder repeat(int repeat, String str) {
        StringBuilder buffer = new StringBuilder(repeat * str.length());
        for (int i = 0; i < repeat; i++) {
            buffer.append(str);
        }
        return buffer;
    }

    public static RecipeDescriptor getRecipeDescriptor(String recipe, Collection<RecipeDescriptor> recipeDescriptors) throws MojoExecutionException {
        return recipeDescriptors.stream()
                .filter(r -> r.getName().equalsIgnoreCase(recipe))
                .findAny()
                .orElseThrow(() -> new MojoExecutionException(String.format(RECIPE_NOT_FOUND_EXCEPTION_MSG, recipe)));
    }

}
