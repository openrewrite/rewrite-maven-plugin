package org.openrewrite.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.style.Autodetect;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.BuildTool;
import org.openrewrite.marker.Generated;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.maven.cache.CompositeMavenPomCache;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.MavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;
import org.openrewrite.maven.internal.RawRepositories;
import org.openrewrite.maven.tree.ProfileActivation;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.internal.ListUtils.map;

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
// GitProvenance - If the project exists in the context of a git repository, all source files (for all modules) will have the same GitProvenance.
// JavaSourceSet - All Java source files and all resource files that exist in src/main or src/test will have a JavaSourceSet marker assigned to them.
// -----------------------------------------------------------------------------------------------------------------
public class MavenMojoProjectParser {

    @Nullable
    static MavenPomCache pomCache;

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
    private final MavenSession mavenSession;
    private final SettingsDecrypter settingsDecrypter;

    @SuppressWarnings("BooleanParameter")
    public MavenMojoProjectParser(Log logger, Path baseDir, boolean pomCacheEnabled, @Nullable String pomCacheDirectory, MavenProject mavenProject, RuntimeInformation runtime, boolean skipMavenParsing, Collection<String> exclusions, int thresholdMb, MavenSession session, SettingsDecrypter settingsDecrypter) {
        this.logger = logger;
        this.baseDir = baseDir;
        this.mavenProject = mavenProject;
        this.pomCacheEnabled = pomCacheEnabled;
        this.pomCacheDirectory = pomCacheDirectory;
        this.skipMavenParsing = skipMavenParsing;
        this.exclusions = exclusions;
        sizeThresholdMb = thresholdMb;
        this.mavenSession = session;
        this.settingsDecrypter = settingsDecrypter;

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

        BuildEnvironment buildEnvironment = BuildEnvironment.build(System::getenv);
        projectProvenance = Stream.of(
                        buildEnvironment,
                        gitProvenance(baseDir, buildEnvironment),
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
    private GitProvenance gitProvenance(Path baseDir, @Nullable BuildEnvironment buildEnvironment) {
        try {
            return GitProvenance.fromProjectDirectory(baseDir, buildEnvironment);
        } catch (Exception e) {
            // Logging at a low level as this is unlikely to happen except in non-git projects, where it is expected
            logger.debug("Unable to determine git provenance", e);
        }
        return null;
    }

    @Nullable
    public Xml.Document parseMaven(ExecutionContext ctx) {
        J.clearCaches();
        if (skipMavenParsing) {
            logger.info("Skipping Maven parsing...");
            return null;
        }

        Set<Path> allPoms = collectPoms(mavenProject, new HashSet<>());
        mavenSession.getProjectDependencyGraph().getUpstreamProjects(mavenProject, true).forEach(p -> collectPoms(p, allPoms));
        MavenParser.Builder mavenParserBuilder = MavenParser.builder().mavenConfig(baseDir.resolve(".mvn/maven.config"));

        MavenSettings settings = buildSettings();
        MavenExecutionContextView mavenExecutionContext = MavenExecutionContextView.view(ctx);
        mavenExecutionContext.setMavenSettings(settings);

        if (pomCacheEnabled) {
            //The default pom cache is enabled as a two-layer cache L1 == in-memory and L2 == RocksDb
            //If the flag is set to false, only the default, in-memory cache is used.
            mavenExecutionContext.setPomCache(getPomCache(pomCacheDirectory, logger));
        }
        List<String> activeProfiles = mavenProject.getActiveProfiles().stream().map(Profile::getId).collect(Collectors.toList());
        if (!activeProfiles.isEmpty()) {
            mavenParserBuilder.activeProfiles(activeProfiles.toArray(new String[]{}));
        }

        Xml.Document maven = mavenParserBuilder
                .build()
                .parse(allPoms, baseDir, ctx)
                .iterator()
                .next();

        for (Marker marker : projectProvenance) {
            maven = maven.withMarkers(maven.getMarkers().addIfAbsent(marker));
        }

        return maven;
    }

    /**
     * Recursively navigate the maven project to collect any poms that are local (on disk)
     *
     * @param project A maven project to examine for any children/parent poms.
     * @param paths A list of paths to poms that have been collected so far.
     * @return All poms associated with the current pom.
     */
    private Set<Path> collectPoms(MavenProject project, Set<Path> paths) {
        paths.add(project.getFile().toPath());

        // children
        if (project.getCollectedProjects() != null) {
            for (MavenProject child : project.getCollectedProjects()) {
                Path path = child.getFile().toPath();
                if (!paths.contains(path)) {
                    collectPoms(child, paths);
                }
            }
        }

        MavenProject parent = project.getParent();
        while (parent != null && parent.getFile() != null) {
            Path path = parent.getFile().toPath();
            if (!paths.contains(path)) {
                collectPoms(parent, paths);
            }
            parent = parent.getParent();
        }
        return paths;
    }

    private static MavenPomCache getPomCache(@Nullable String pomCacheDirectory, Log logger) {
        if (pomCache == null) {
            if (isJvm64Bit()) {
                try {
                    if (pomCacheDirectory == null) {
                        //Default directory in the RocksdbMavenPomCache is ".rewrite-cache"
                        pomCache = new CompositeMavenPomCache(
                                new InMemoryMavenPomCache(),
                                new RocksdbMavenPomCache(Paths.get(System.getProperty("user.home")))
                        );
                    } else {
                        pomCache = new CompositeMavenPomCache(
                                new InMemoryMavenPomCache(),
                                new RocksdbMavenPomCache(Paths.get(pomCacheDirectory))
                        );
                    }
                } catch (Exception e) {
                    logger.warn("Unable to initialize RocksdbMavenPomCache, falling back to InMemoryMavenPomCache");
                    logger.debug(e);
                }
            } else {
                logger.warn("RocksdbMavenPomCache is not supported on 32-bit JVM. falling back to InMemoryMavenPomCache");
            }
        }
        if (pomCache == null) {
            pomCache = new InMemoryMavenPomCache();
        }
        return pomCache;
    }

    private static boolean isJvm64Bit() {
        //It appears most JVM vendors set this property. Only return false if the
        //property has been set AND it is set to 32.
        return !System.getProperty("sun.arch.data.model", "64").equals("32");
    }
    private MavenSettings buildSettings() {
        MavenExecutionRequest mer = mavenSession.getRequest();

        MavenSettings.Profiles profiles = new MavenSettings.Profiles();
        profiles.setProfiles(
                mer.getProfiles().stream().map(p -> new MavenSettings.Profile(
                                p.getId(),
                                p.getActivation() == null ? null : new ProfileActivation(
                                        p.getActivation().isActiveByDefault(),
                                        p.getActivation().getJdk(),
                                        p.getActivation().getProperty() == null ? null : new ProfileActivation.Property(
                                                p.getActivation().getProperty().getName(),
                                                p.getActivation().getProperty().getValue()
                                        )
                                ),
                                buildRawRepositories(p.getRepositories())
                        )
                ).collect(toList()));

        MavenSettings.ActiveProfiles activeProfiles = new MavenSettings.ActiveProfiles();
        activeProfiles.setActiveProfiles(mer.getActiveProfiles());

        MavenSettings.Mirrors mirrors = new MavenSettings.Mirrors();
        mirrors.setMirrors(
                mer.getMirrors().stream().map(m -> new MavenSettings.Mirror(
                        m.getId(),
                        m.getUrl(),
                        m.getMirrorOf(),
                        null,
                        null
                )).collect(toList())
        );

        MavenSettings.Servers servers = new MavenSettings.Servers();
        servers.setServers(mer.getServers().stream().map(s -> {
            SettingsDecryptionRequest decryptionRequest = new DefaultSettingsDecryptionRequest(s);
            SettingsDecryptionResult decryptionResult = settingsDecrypter.decrypt(decryptionRequest);
            return new MavenSettings.Server(
                    s.getId(),
                    s.getUsername(),
                    decryptionResult.getServer().getPassword()
            );
        }).collect(toList()));

        return new MavenSettings(profiles, activeProfiles, mirrors, servers);
    }

    @Nullable
    private static RawRepositories buildRawRepositories(@Nullable List<Repository> repositoriesToMap) {
        if (repositoriesToMap == null) {
            return null;
        }

        RawRepositories rawRepositories = new RawRepositories();
        List<RawRepositories.Repository> transformedRepositories = repositoriesToMap.stream().map(r -> new RawRepositories.Repository(
                r.getId(),
                r.getUrl(),
                r.getReleases() == null ? null : new RawRepositories.ArtifactPolicy(r.getReleases().isEnabled()),
                r.getSnapshots() == null ? null : new RawRepositories.ArtifactPolicy(r.getSnapshots().isEnabled())
        )).collect(toList());
        rawRepositories.setRepositories(transformedRepositories);
        return rawRepositories;
    }

    private Set<Path> skipOtherMavenProjects() {
        return mavenSession.getProjects().stream()
                .filter(o -> o != mavenSession.getCurrentProject())
                .map(o -> o.getBasedir().toPath())
                .collect(Collectors.toSet());
    }

    public List<SourceFile> listSourceFiles(Iterable<NamedStyles> styles,
                                            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

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
        Set<Path> alreadyParsed = new HashSet<>(mainJavaSources);

        List<SourceFile> sourceFiles = new ArrayList<>();
        Xml.Document maven = parseMaven(ctx);
        if (maven != null) {
            sourceFiles.add(maven);
            alreadyParsed.add(baseDir.resolve(maven.getSourcePath()));
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
        sourceFiles.addAll(ListUtils.map(maybeAutodetectStyles(javaParser.parse(mainJavaSources, baseDir, ctx), styles),
                addProvenance(baseDir, projectProvenance, generatedSourcePaths)));

        ResourceParser rp = new ResourceParser(baseDir, logger, exclusions, sizeThresholdMb, skipOtherMavenProjects());

        // Any resources parsed from "main/resources" should also have the main source set added to them.
        sourceFiles.addAll(ListUtils.map(
                rp.parse(mavenProject.getBasedir().toPath().resolve("src/main/resources"), alreadyParsed),
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
        List<Path> testJavaSources = listJavaSources(mavenProject.getBuild().getTestSourceDirectory());
        alreadyParsed.addAll(testJavaSources);

        sourceFiles.addAll(ListUtils.map(
                maybeAutodetectStyles(javaParser.parse(testJavaSources, baseDir, ctx), styles),
                addProvenance(baseDir, projectProvenance, null)));

        // Any resources parsed from "test/resources" should also have the test source set added to them.
        sourceFiles.addAll(ListUtils.map(
                rp.parse(mavenProject.getBasedir().toPath().resolve("src/test/resources"), alreadyParsed),
                addProvenance(baseDir, ListUtils.concat(projectProvenance, javaParser.getSourceSet(ctx)), null)));

        // Parse non-java, non-resource files
        sourceFiles.addAll(ListUtils.map(
                rp.parse(mavenSession.getCurrentProject().getBasedir().toPath(), alreadyParsed),
                addProvenance(baseDir, projectProvenance, null)
        ));

        return sourceFiles;
    }

    private List<J.CompilationUnit> maybeAutodetectStyles(List<J.CompilationUnit> sourceFiles, @Nullable Iterable<NamedStyles> styles) {
        if (styles != null) {
            return sourceFiles;
        }
        Autodetect autodetect = Autodetect.detect(sourceFiles);

        return map(sourceFiles, cu -> {
            List<Marker> markers = ListUtils.concat(map(cu.getMarkers().getMarkers(), m -> m instanceof NamedStyles ? null : m), autodetect);
            return cu.withMarkers(cu.getMarkers().withMarkers(markers));
        });
    }

    private static <S extends SourceFile> UnaryOperator<S> addProvenance(Path baseDir, List<Marker> provenance, @Nullable Collection<Path> generatedSources) {
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
        try (Stream<Path> files = Files.find(sourceRoot, 16, (f, a) -> !a.isDirectory() && f.toString().endsWith(".java"))) {
            return files.collect(toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list Java source files", e);
        }
    }
}
