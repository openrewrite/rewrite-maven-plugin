package org.openrewrite.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.Generated;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.style.NamedStyles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;

// -----------------------------------------------------------------------------------------------------------------
// Notes About Provenance Information:
//
// There are always three markers applied to each source file and there can potentially be up to five provenance markers
// total:
//
// BuildTool     - What build tool was used to compile the source file (This will always be Maven)
// JavaVersion   - What Java version/vendor was used when compiling the source file.
// JavaProject   - For each maven module/sub-module, the same JavaProject will be associated with ALL source files belonging to that module.
//
// Optional:
//
// GitProvenance - If the entire project exists in the context of a git repository, all source files (for all modules) will have the same GitProvenance.
// JavaSourceSet - All Java source files and all resource files that exist in src/main or src/test will have a JavaSourceSet marker assigned to them.
// -----------------------------------------------------------------------------------------------------------------
public class MavenMojoProjectParser {
    private final Log logger;
    private final Path baseDir;
    private final MavenProject mavenProject;
    private final List<Marker> projectProvenance;
    private final boolean pomCacheEnabled;
    @Nullable
    private final String pomCacheDirectory;
    private final boolean skipMavenParsing;
    private final Collection<String> exclusions;
    private final int sizeThresholdMb;

    public MavenMojoProjectParser(Log logger, Path baseDir, boolean pomCacheEnabled, @Nullable String pomCacheDirectory, MavenProject mavenProject, RuntimeInformation runtime, boolean skipMavenParsing, Collection<String> exclusions, int thresholdMb) {
        this.logger = logger;
        this.baseDir = baseDir;
        this.mavenProject = mavenProject;
        this.pomCacheEnabled = pomCacheEnabled;
        this.pomCacheDirectory = pomCacheDirectory;
        this.skipMavenParsing = skipMavenParsing;
        this.exclusions = exclusions;
        sizeThresholdMb = thresholdMb;

        String javaRuntimeVersion = System.getProperty("java.runtime.version");
        String javaVendor = System.getProperty("java.vm.vendor");
        String sourceCompatibility = javaRuntimeVersion;
        String targetCompatibility = javaRuntimeVersion;

        String propertiesSourceCompatibility = (String) mavenProject.getProperties().get("maven.compiler.source");
        if (propertiesSourceCompatibility != null) {
            sourceCompatibility = propertiesSourceCompatibility;
        }
        String propertiesTargetCompatibility = (String) mavenProject.getProperties().get("maven.compiler.target");
        if (propertiesTargetCompatibility != null) {
            targetCompatibility = propertiesTargetCompatibility;
        }

        projectProvenance = Stream.of(gitProvenance(baseDir),
                        new BuildTool(randomId(), BuildTool.Type.Maven, runtime.getMavenVersion()),
                        new JavaVersion(randomId(), javaRuntimeVersion, javaVendor, sourceCompatibility, targetCompatibility),
                        new JavaProject(randomId(), mavenProject.getName(), new JavaProject.Publication(
                                mavenProject.getGroupId(),
                                mavenProject.getArtifactId(),
                                mavenProject.getVersion()
                        )))
                .filter(Objects::nonNull)
                .collect(toList());
    }

    @Nullable
    private GitProvenance gitProvenance(Path baseDir) {
        try {
            return GitProvenance.fromProjectDirectory(baseDir);
        } catch (Exception e) {
            // Logging at a low level as this is unlikely to happen except in non-git projects, where it is expected
            logger.debug("Unable to determine git provenance", e);
        }
        return null;
    }

    @Nullable
    public Maven parseMaven(ExecutionContext ctx) {
        if (skipMavenParsing) {
            logger.info("Skipping Maven parsing...");
            return null;
        }

        List<Path> allPoms = new ArrayList<>();
        allPoms.add(mavenProject.getFile().toPath());

        // children
        if (mavenProject.getCollectedProjects() != null) {
            mavenProject.getCollectedProjects().stream()
                    .filter(collectedProject -> collectedProject != mavenProject)
                    .map(collectedProject -> collectedProject.getFile().toPath())
                    .forEach(allPoms::add);
        }

        MavenProject parent = mavenProject.getParent();
        while (parent != null && parent.getFile() != null) {
            allPoms.add(parent.getFile().toPath());
            parent = parent.getParent();
        }
        MavenParser.Builder mavenParserBuilder = MavenParser.builder().mavenConfig(baseDir.resolve(".mvn/maven.config"));

        if (pomCacheEnabled) {
            try {
                if (pomCacheDirectory == null) {
                    //Default directory in the RocksdbMavenPomCache is ".rewrite-cache"
                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(System.getProperty("user.home"))));
                } else {
                    mavenParserBuilder.cache(new RocksdbMavenPomCache(Paths.get(pomCacheDirectory)));
                }
            } catch (Exception e) {
                logger.warn("Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache");
                logger.debug(e);
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
                                    logger.warn("Unable to load Maven settings from user home directory. Skipping.", e);
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
        Maven pom = mavenParserBuilder
                .build()
                .parse(allPoms, baseDir, ctx)
                .iterator()
                .next();

        for (Marker marker : projectProvenance) {
            pom = pom.withMarkers(pom.getMarkers().addIfAbsent(marker));
        }

        return pom;
    }

    public List<SourceFile> listSourceFiles(Iterable<NamedStyles> styles,
                                            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        Set<Path> alreadyParsed = new HashSet<>();
        JavaParser javaParser = JavaParser.fromJavaVersion()
                .styles(styles)
                .logCompilationWarningsAndErrors(false)
                .build();

        // Some annotation processors output generated sources to the /target directory
        List<Path> generatedSourcePaths = listJavaSources(mavenProject.getBuild().getDirectory());
        List<Path> mainJavaSources = Stream.concat(
                generatedSourcePaths.stream(),
                listJavaSources(mavenProject.getBuild().getSourceDirectory()).stream()
        ).collect(toList());

        List<SourceFile> sourceFiles = new ArrayList<>();
        Maven maven = parseMaven(ctx);
        if (maven != null) {
            sourceFiles.add(maven);
            alreadyParsed.add(maven.getSourcePath());
        }

        logger.info("Parsing Java main files...");
        List<Path> dependencies = mavenProject.getCompileClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());
        javaParser.setClasspath(dependencies);
        javaParser.setSourceSet("main");

        // JavaParser will add SourceSet Markers to any Java SourceFile, so only adding the project provenance info to
        // java source.
        sourceFiles.addAll(ListUtils.map(javaParser.parse(mainJavaSources, baseDir, ctx),
                addProvenance(baseDir, projectProvenance, generatedSourcePaths)));

        ResourceParser rp = new ResourceParser(logger, exclusions, sizeThresholdMb);

        // Any resources parsed from "main/resources" should also have the main source set added to them.
        sourceFiles.addAll(ListUtils.map(
                rp.parse(baseDir, mavenProject.getBasedir().toPath().resolve("src/main/resources"), alreadyParsed),
                addProvenance(baseDir, ListUtils.concat(projectProvenance, javaParser.getSourceSet(ctx)), null)));

        logger.info("Parsing Java test files...");
        List<Path> testDependencies = mavenProject.getTestClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());

        javaParser.setClasspath(testDependencies);
        javaParser.setSourceSet("test");

        // JavaParser will add SourceSet Markers to any Java SourceFile, so only adding the project provenance info to
        // java source.
        sourceFiles.addAll(ListUtils.map(
                javaParser.parse(listJavaSources(mavenProject.getBuild().getTestSourceDirectory()), baseDir, ctx),
                addProvenance(baseDir, projectProvenance, null)));

        // Any resources parsed from "test/resources" should also have the test source set added to them.
        sourceFiles.addAll(ListUtils.map(
                rp.parse(baseDir, mavenProject.getBasedir().toPath().resolve("src/test/resources"), alreadyParsed),
                addProvenance(baseDir, ListUtils.concat(projectProvenance, javaParser.getSourceSet(ctx)), null)));

        // Parse non-java, non-resource files
        sourceFiles.addAll(ListUtils.map(
                rp.parse(baseDir, mavenProject.getBasedir().toPath(), alreadyParsed),
                addProvenance(baseDir, projectProvenance, null)
        ));

        return sourceFiles;
    }

    private <S extends SourceFile> UnaryOperator<S> addProvenance(Path baseDir,
                                                                  List<Marker> provenance, @Nullable Collection<Path> generatedSources) {
        return s -> {
            for (Marker marker : provenance) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(marker));
            }
            if (generatedSources != null && generatedSources.contains(baseDir.resolve(s.getSourcePath()))) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(new Generated(randomId())));
            }
            return s;
        };
    }

    public static List<Path> listJavaSources(String sourceDirectory) throws MojoExecutionException {
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
