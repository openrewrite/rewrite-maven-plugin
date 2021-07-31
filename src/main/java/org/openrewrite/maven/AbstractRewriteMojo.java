package org.openrewrite.maven;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import io.micrometer.core.instrument.Metrics;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.openrewrite.*;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.NonNull;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.marker.JavaProvenance;
import org.openrewrite.java.style.Checkstyle;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.maven.tree.Pom;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.PropertiesVisitor;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.YamlVisitor;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;

public abstract class AbstractRewriteMojo extends AbstractMojo {


    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(property = "configLocation", defaultValue = "${maven.multiModuleProjectDirectory}/rewrite.yml")
    String configLocation;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @SuppressWarnings("NotNullFieldNotInitialized")
    @Component
    protected RuntimeInformation runtime;

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

    @Nullable
    @Parameter(property = "checkstyleConfigFile")
    private String checkstyleConfigFile;

    /**
     * Whether to throw an exception if an activeRecipe fails configuration validation.
     * This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured.
     * <p>
     * For the time, this default is "false" to prevent one improperly recipe from failing the build.
     * In the future, this default may be changed to "true" to be more restrictive.
     */
    @Parameter(property = "failOnInvalidActiveRecipes", defaultValue = "false")
    private boolean failOnInvalidActiveRecipes;

    private static final String RECIPE_NOT_FOUND_EXCEPTION_MSG = "Could not find recipe '%s' among available recipes";

    protected Environment environment() throws MojoExecutionException {
        Environment.Builder env = Environment
                .builder(project.getProperties())
                .scanRuntimeClasspath()
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
                    //Default directory in the RocksdbMavenPomCache is ".rewrite-cache"
                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(System.getProperty("user.home"))));
                } else {
                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(pomCacheDirectory)));
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
            if (settings != null) {
                new MavenExecutionContextView(ctx).setMavenSettings(settings);
                if (settings.getActiveProfiles() != null) {
                    mavenParserBuilder.activeProfiles(settings.getActiveProfiles().getActiveProfiles().toArray(new String[]{}));
                }
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
            Metrics.addRegistry(meterRegistryProvider.registry());

            Path baseDir = getBaseDir();
            getLog().info(String.format("Using active recipe(s) %s", activeRecipes));
            getLog().info(String.format("Using active styles(s) %s", activeStyles));
            if (activeRecipes.isEmpty()) {
                return new ResultsContainer(baseDir, emptyList());
            }

            Environment env = environment();

            List<NamedStyles> styles;
            styles = env.activateStyles(activeStyles);
            try {
                Plugin checkstylePlugin = project.getPlugin("org.apache.maven.plugins:maven-checkstyle-plugin");
                if (checkstyleConfigFile != null && !checkstyleConfigFile.isEmpty()) {
                    styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(Paths.get(checkstyleConfigFile), emptyMap()));
                } else if (checkstylePlugin != null) {
                    Object checkstyleConfRaw = checkstylePlugin.getConfiguration();
                    if (checkstyleConfRaw instanceof Xpp3Dom) {
                        Xpp3Dom xmlCheckstyleConf = (Xpp3Dom) checkstyleConfRaw;
                        Xpp3Dom xmlConfigLocation = xmlCheckstyleConf.getChild("configLocation");

                        if (xmlConfigLocation == null) {
                            // When no config location is specified, the maven-checkstyle-plugin falls back on sun_checks.xml
                            try (InputStream is = Checker.class.getResourceAsStream("/sun_checks.xml")) {
                                if (is != null) {
                                    styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(is, emptyMap()));
                                }
                            }
                        } else {
                            Path configPath = Paths.get(xmlConfigLocation.getValue());
                            styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(configPath, emptyMap()));
                        }

                    }
                }
            } catch (Exception e) {
                getLog().warn("Unable to parse checkstyle configuration. Checkstyle will not inform rewrite execution.", e);
            }

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

            JavaParser javaParser = JavaParser.fromJavaVersion()
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
                    .build();

            ExecutionContext ctx = executionContext();


            List<SourceFile> mainSourceFiles = new ArrayList<>(512);
            List<SourceFile> testSourceFiles = new ArrayList<>(512);
            Set<Path> mainResources = new HashSet<>(512);
            Set<Path> testResources = new HashSet<>(512);

            getLog().info("Parsing Java files...");
            mainSourceFiles.addAll(javaParser.parse(listJavaSources(project.getBuild().getSourceDirectory()), baseDir, ctx));
            testSourceFiles.addAll(javaParser.parse(listJavaSources(project.getBuild().getTestSourceDirectory()), baseDir, ctx));


            for (Resource resource : project.getBuild().getResources()) {
                addToResources(ctx, mainResources, resource);
            }
            for (Resource resource : project.getBuild().getTestResources()) {
                addToResources(ctx, testResources, resource);
            }

            Set<Class<?>> recipeTypes = new HashSet<>();
            discoverRecipeTypes(recipe, recipeTypes);

            if (recipeTypes.contains(YamlVisitor.class)) {
                getLog().info("Parsing YAML files...");
                YamlParser yamlParser = new YamlParser();
                mainSourceFiles.addAll(
                        yamlParser.parse(
                            mainResources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".yml") || it.getFileName().toString().endsWith(".yaml"))
                                    .collect(toList()),
                            baseDir,
                            ctx
                        )
                );
                testSourceFiles.addAll(
                        yamlParser.parse(
                                testResources.stream()
                                        .filter(it -> it.getFileName().toString().endsWith(".yml") || it.getFileName().toString().endsWith(".yaml"))
                                        .collect(toList()),
                                baseDir,
                                ctx
                        )
                );
            } else {
                getLog().info("Skipping YAML files because there are no active YAML recipes.");
            }

            if (recipeTypes.contains(PropertiesVisitor.class)) {
                getLog().info("Parsing properties files...");
                PropertiesParser propertiesParser = new PropertiesParser();
                mainSourceFiles.addAll(
                        propertiesParser.parse(
                                mainResources.stream()
                                        .filter(it -> it.getFileName().toString().endsWith(".properties"))
                                        .collect(toList()),
                                baseDir,
                                ctx
                        )
                );
                testSourceFiles.addAll(
                        propertiesParser.parse(
                                testResources.stream()
                                        .filter(it -> it.getFileName().toString().endsWith(".properties"))
                                        .collect(toList()),
                                baseDir,
                                ctx
                        )
                );
            } else {
                getLog().info("Skipping properties files because there are no active properties recipes.");
            }

            if (recipeTypes.contains(XmlVisitor.class)) {
                getLog().info("Parsing XML files...");
                XmlParser xmlParser = new XmlParser();
                mainSourceFiles.addAll(
                        xmlParser.parse(
                                mainResources.stream()
                                        .filter(it -> it.getFileName().toString().endsWith(".xml"))
                                        .collect(toList()),
                                baseDir,
                                ctx
                        )
                );
                testSourceFiles.addAll(
                        xmlParser.parse(
                                testResources.stream()
                                        .filter(it -> it.getFileName().toString().endsWith(".xml"))
                                        .collect(toList()),
                                baseDir,
                                ctx
                        )
                );
            } else {
                getLog().info("Skipping XML files because there are no active XML recipes.");
            }

            JavaProvenance mainProvenance = getJavaProvenance("main");
            JavaProvenance testProvenance = getJavaProvenance("test");

            List<SourceFile> sourceFiles = new ArrayList<>(
                    ListUtils.map(mainSourceFiles, s -> s.withMarkers(s.getMarkers().addIfAbsent(mainProvenance)))
            );
            sourceFiles.addAll(
                    ListUtils.map(testSourceFiles, s -> s.withMarkers(s.getMarkers().addIfAbsent(testProvenance)))
            );

            if (recipeTypes.contains(MavenVisitor.class)) {
                getLog().info("Parsing POM...");
                Maven pomAst = parseMaven(baseDir, ctx);
                sourceFiles.add(pomAst);
            } else {
                getLog().info("Skipping Maven POM files because there are no active Maven recipes.");
            }

            getLog().info("Running recipe(s)...");
            List<Result> results = recipe.run(sourceFiles, ctx);

            Metrics.removeRegistry(meterRegistryProvider.registry());

            return new ResultsContainer(baseDir, results);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Dependency resolution required", e);
        }
    }

    private JavaProvenance getJavaProvenance(String sourceSet) {

        String javaRuntimeVersion = System.getProperty("java.runtime.version");
        String javaVendor = System.getProperty("java.vm.vendor");
        String sourceCompatibility = javaRuntimeVersion;
        String targetCompatibility = javaRuntimeVersion;

        String propertiesSourceCompatibility = (String) project.getProperties().get("maven.compiler.source");
        if (propertiesSourceCompatibility != null) {
            sourceCompatibility = propertiesSourceCompatibility;
        }
        String propertiesTargetCompatibility = (String) project.getProperties().get("maven.compiler.target");
        if (propertiesTargetCompatibility != null) {
            targetCompatibility = propertiesTargetCompatibility;
        }


        JavaProvenance.BuildTool buildTool = new JavaProvenance.BuildTool(JavaProvenance.BuildTool.Type.Maven,
                runtime.getMavenVersion());

        JavaProvenance.JavaVersion javaVersion = new JavaProvenance.JavaVersion(
                javaRuntimeVersion,
                javaVendor,
                sourceCompatibility,
                targetCompatibility
        );

        JavaProvenance.Publication publication = new JavaProvenance.Publication(
                project.getGroupId(),
                project.getArtifactId(),
                project.getVersion()
        );

        return new JavaProvenance(
                randomId(),
                project.getName(),
                sourceSet,
                buildTool,
                javaVersion,
                publication
        );
    }

    private void discoverRecipeTypes(Recipe recipe, Set<Class<?>> recipeTypes) {
        for (Recipe next : recipe.getRecipeList()) {
            discoverRecipeTypes(next, recipeTypes);
        }

        try {
            Method getVisitor = recipe.getClass().getDeclaredMethod("getVisitor");
            getVisitor.setAccessible(true);
            Object visitor = getVisitor.invoke(recipe);
            if (visitor instanceof MavenVisitor) {
                recipeTypes.add(MavenVisitor.class);
            } else if (visitor instanceof JavaVisitor) {
                recipeTypes.add(JavaVisitor.class);
            } else if (visitor instanceof PropertiesVisitor) {
                recipeTypes.add(PropertiesVisitor.class);
            } else if (visitor instanceof XmlVisitor) {
                recipeTypes.add(XmlVisitor.class);
            } else if (visitor instanceof YamlVisitor) {
                recipeTypes.add(YamlVisitor.class);
            }
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ignored) {
            // not every recipe will implement getVisitor() directly, e.g. CompositeRecipe.
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
            getLog().warn("    " + recipe.getName());
        }
    }

    public static RecipeDescriptor getRecipeDescriptor(String recipe, Collection<RecipeDescriptor> recipeDescriptors) throws MojoExecutionException {
        return recipeDescriptors.stream()
                .filter(r -> r.getName().equalsIgnoreCase(recipe))
                .findAny()
                .orElseThrow(() -> new MojoExecutionException(String.format(RECIPE_NOT_FOUND_EXCEPTION_MSG, recipe)));
    }

}
