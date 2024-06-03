/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
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
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ParseExceptionResult;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.java.tree.J;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.marker.*;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.MavenPomCache;
import org.openrewrite.maven.internal.RawRepositories;
import org.openrewrite.maven.tree.ProfileActivation;
import org.openrewrite.maven.utilities.MavenWrapper;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.tree.ParseError;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.*;
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
// JavaProject   - For each maven module/submodule, the same JavaProject will be associated with ALL source files belonging to that module.
//
// Optional:
//
// GitProvenance - If the project exists in the context of a git repository, all source files (for all modules) will have the same GitProvenance.
// JavaSourceSet - All Java source files and all resource files that exist in src/main or src/test will have a JavaSourceSet marker assigned to them.
// -----------------------------------------------------------------------------------------------------------------
public class MavenMojoProjectParser {

    private static final String MVN_MAVEN_CONFIG = ".mvn/maven.config";
    @Nullable
    public static MavenPomCache POM_CACHE;

    private final Log logger;
    private final AtomicBoolean firstWarningLogged = new AtomicBoolean(false);
    private final Path baseDir;
    private final boolean pomCacheEnabled;

    @Nullable
    private final String pomCacheDirectory;

    private final boolean skipMavenParsing;

    private final BuildTool buildTool;

    private final Collection<String> exclusions;
    private final Collection<String> plainTextMasks;
    private final int sizeThresholdMb;
    private final MavenSession mavenSession;
    private final SettingsDecrypter settingsDecrypter;
    private final boolean runPerSubmodule;
    private final boolean parseAdditionalResources;

    @SuppressWarnings("BooleanParameter")
    public MavenMojoProjectParser(Log logger, Path baseDir, boolean pomCacheEnabled, @Nullable String pomCacheDirectory, RuntimeInformation runtime, boolean skipMavenParsing, Collection<String> exclusions, Collection<String> plainTextMasks, int sizeThresholdMb, MavenSession session, SettingsDecrypter settingsDecrypter, boolean runPerSubmodule, boolean parseAdditionalResources) {
        this.logger = logger;
        this.baseDir = baseDir;
        this.pomCacheEnabled = pomCacheEnabled;
        this.pomCacheDirectory = pomCacheDirectory;
        this.skipMavenParsing = skipMavenParsing;
        this.buildTool = new BuildTool(randomId(), BuildTool.Type.Maven, runtime.getMavenVersion());
        this.exclusions = exclusions;
        this.plainTextMasks = plainTextMasks;
        this.sizeThresholdMb = sizeThresholdMb;
        this.mavenSession = session;
        this.settingsDecrypter = settingsDecrypter;
        this.runPerSubmodule = runPerSubmodule;
        this.parseAdditionalResources = parseAdditionalResources;
    }

    public Stream<SourceFile> listSourceFiles(MavenProject mavenProject, List<NamedStyles> styles,
                                              ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        if (runPerSubmodule) {
            //If running per submodule, parse the source files for only the current project.
            List<Marker> projectProvenance = generateProvenance(mavenProject);
            Xml.Document maven = parseMaven(mavenProject, projectProvenance, ctx);
            return listSourceFiles(mavenProject, maven, projectProvenance, styles, ctx);
        } else {
            //If running across all project, iterate and parse source files from each project
            Map<MavenProject, List<Marker>> projectProvenances = mavenSession.getProjects().stream()
                    .collect(Collectors.toMap(Function.identity(), this::generateProvenance));
            Map<MavenProject, Xml.Document> projectMap = parseMaven(mavenSession.getProjects(), projectProvenances, ctx);
            return mavenSession.getProjects().stream()
                    .flatMap(project -> {
                        List<Marker> projectProvenance = projectProvenances.get(project);
                        try {
                            return listSourceFiles(project, projectMap.get(project), projectProvenance, styles, ctx);
                        } catch (DependencyResolutionRequiredException | MojoExecutionException e) {
                            throw sneakyThrow(e);
                        }
                    });
        }
    }

    public Stream<SourceFile> listSourceFiles(MavenProject mavenProject, @Nullable Xml.Document maven, List<Marker> projectProvenance, List<NamedStyles> styles,
                                              ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        Stream<SourceFile> sourceFiles = Stream.empty();
        Set<Path> alreadyParsed = new HashSet<>();

        if (maven != null) {
            sourceFiles = Stream.of(maven);
            alreadyParsed.add(baseDir.resolve(maven.getSourcePath()));
        }

        JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder = JavaParser.fromJavaVersion()
                .styles(styles)
                .logCompilationWarningsAndErrors(false);
        getCharset(mavenProject).ifPresent(javaParserBuilder::charset);

        // todo, add styles from autoDetect
        KotlinParser.Builder kotlinParserBuilder = KotlinParser.builder();
        ResourceParser rp = new ResourceParser(baseDir, logger, exclusions, plainTextMasks, sizeThresholdMb, pathsToOtherMavenProjects(mavenProject),
                javaParserBuilder.clone(), kotlinParserBuilder.clone(), ctx);

        sourceFiles = Stream.concat(sourceFiles, processMainSources(mavenProject, javaParserBuilder.clone(), kotlinParserBuilder.clone(), rp, projectProvenance, alreadyParsed, ctx));
        sourceFiles = Stream.concat(sourceFiles, processTestSources(mavenProject, javaParserBuilder.clone(), kotlinParserBuilder.clone(), rp, projectProvenance, alreadyParsed, ctx));
        Collection<PathMatcher> exclusionMatchers = exclusions.stream()
                .map(pattern -> baseDir.getFileSystem().getPathMatcher("glob:" + pattern))
                .collect(toList());
        sourceFiles = sourceFiles.map(sourceFile -> {
            if (sourceFile instanceof J.CompilationUnit) {
                for (PathMatcher excluded : exclusionMatchers) {
                    if (excluded.matches(sourceFile.getSourcePath())) {
                        return null;
                    }
                }
            }
            return sourceFile;
        }).filter(Objects::nonNull);

        // Collect any additional files that were not parsed above.
        int sourcesParsedBefore = alreadyParsed.size();
        Stream<SourceFile> parsedResourceFiles;
        if (parseAdditionalResources) {
            parsedResourceFiles = rp.parse(mavenProject.getBasedir().toPath(), alreadyParsed)
                    .map(addProvenance(baseDir, projectProvenance, null));
            logDebug(mavenProject, "Parsed " + (alreadyParsed.size() - sourcesParsedBefore) + " additional files found within the project.");
        } else {
            // Only parse Maven wrapper related files, such that UpdateMavenWrapper can use the version information.
            parsedResourceFiles = Stream.of(
                            Paths.get(MVN_MAVEN_CONFIG),
                            MavenWrapper.WRAPPER_BATCH_LOCATION,
                            MavenWrapper.WRAPPER_JAR_LOCATION,
                            MavenWrapper.WRAPPER_PROPERTIES_LOCATION,
                            MavenWrapper.WRAPPER_SCRIPT_LOCATION)
                    .flatMap(path -> rp.parse(mavenProject.getBasedir().toPath().resolve(path), alreadyParsed))
                    .map(addProvenance(baseDir, projectProvenance, null));
            logDebug(mavenProject, "Parsed " + (alreadyParsed.size() - sourcesParsedBefore) + " Maven wrapper files found within the project.");
        }
        sourceFiles = Stream.concat(sourceFiles, parsedResourceFiles);

        // log parse errors here at the end, so that we don't log parse errors for files that were excluded
        return sourceFiles.map(this::logParseErrors);
    }

    private static Optional<Charset> getCharset(MavenProject mavenProject) {
        String compilerPluginKey = "org.apache.maven.plugins:maven-compiler-plugin";
        Plugin plugin = Optional.ofNullable(mavenProject.getPlugin(compilerPluginKey))
                .orElseGet(() -> mavenProject.getPluginManagement().getPluginsAsMap().get(compilerPluginKey));
        if (plugin != null && plugin.getConfiguration() instanceof Xpp3Dom) {
            Xpp3Dom encoding = ((Xpp3Dom) plugin.getConfiguration()).getChild("encoding");
            if (encoding != null && StringUtils.isNotEmpty(encoding.getValue())) {
                return Optional.of(Charset.forName(encoding.getValue()));
            }
        }

        Object mavenSourceEncoding = mavenProject.getProperties().get("project.build.sourceEncoding");
        if (mavenSourceEncoding != null) {
            return Optional.of(Charset.forName(mavenSourceEncoding.toString()));
        }
        return Optional.empty();
    }

    private SourceFile logParseErrors(SourceFile source) {
        if (source instanceof ParseError) {
            if (firstWarningLogged.compareAndSet(false, true)) {
                logger.warn("There were problems parsing some source files" +
                            (mavenSession.getRequest().isShowErrors() ? "" : ", run with --errors to see full stack traces"));
            }
            logger.warn("There were problems parsing " + source.getSourcePath());
            if (mavenSession.getRequest().isShowErrors()) {
                source.getMarkers().findFirst(ParseExceptionResult.class).map(ParseExceptionResult::getMessage).ifPresent(logger::warn);
            }
        }
        return source;
    }

    public List<Marker> generateProvenance(MavenProject mavenProject) {
        BuildEnvironment buildEnvironment = BuildEnvironment.build(System::getenv);
        return Stream.of(
                        buildEnvironment,
                        gitProvenance(baseDir, buildEnvironment),
                        OperatingSystemProvenance.current(),
                        buildTool,
                        new JavaProject(randomId(), mavenProject.getName(), new JavaProject.Publication(
                                mavenProject.getGroupId(),
                                mavenProject.getArtifactId(),
                                mavenProject.getVersion()
                        )))
                .filter(Objects::nonNull)
                .collect(toList());
    }

    private static JavaVersion getSrcMainJavaVersion(MavenProject mavenProject) {
        String sourceCompatibility = null;
        String targetCompatibility = null;

        Plugin compilerPlugin = mavenProject.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (compilerPlugin != null && compilerPlugin.getConfiguration() instanceof Xpp3Dom) {
            Xpp3Dom dom = (Xpp3Dom) compilerPlugin.getConfiguration();
            Xpp3Dom release = dom.getChild("release");
            if (release != null && StringUtils.isNotEmpty(release.getValue()) && !release.getValue().contains("${")) {
                sourceCompatibility = release.getValue();
                targetCompatibility = release.getValue();
            } else {
                Xpp3Dom source = dom.getChild("source");
                if (source != null && StringUtils.isNotEmpty(source.getValue()) && !source.getValue().contains("${")) {
                    sourceCompatibility = source.getValue();
                }
                Xpp3Dom target = dom.getChild("target");
                if (target != null && StringUtils.isNotEmpty(target.getValue()) && !target.getValue().contains("${")) {
                    targetCompatibility = target.getValue();
                }
            }
        }

        if (sourceCompatibility == null || targetCompatibility == null) {
            String propertiesReleaseCompatibility = (String) mavenProject.getProperties().get("maven.compiler.release");
            if (propertiesReleaseCompatibility != null) {
                sourceCompatibility = propertiesReleaseCompatibility;
                targetCompatibility = propertiesReleaseCompatibility;
            } else {
                String propertiesSourceCompatibility = (String) mavenProject.getProperties().get("maven.compiler.source");
                if (sourceCompatibility == null && propertiesSourceCompatibility != null) {
                    sourceCompatibility = propertiesSourceCompatibility;
                }
                String propertiesTargetCompatibility = (String) mavenProject.getProperties().get("maven.compiler.target");
                if (targetCompatibility == null && propertiesTargetCompatibility != null) {
                    targetCompatibility = propertiesTargetCompatibility;
                }
            }
        }

        return getJavaVersionMarker(sourceCompatibility, targetCompatibility);
    }

    static JavaVersion getSrcTestJavaVersion(MavenProject mavenProject) {
        String sourceCompatibility = null;
        String targetCompatibility = null;

        Plugin compilerPlugin = mavenProject.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (compilerPlugin != null && compilerPlugin.getConfiguration() instanceof Xpp3Dom) {
            Xpp3Dom dom = (Xpp3Dom) compilerPlugin.getConfiguration();
            Xpp3Dom release = dom.getChild("testRelease");
            if (release != null && StringUtils.isNotEmpty(release.getValue()) && !release.getValue().contains("${")) {
                sourceCompatibility = release.getValue();
                targetCompatibility = release.getValue();
            } else {
                Xpp3Dom source = dom.getChild("testSource");
                if (source != null && StringUtils.isNotEmpty(source.getValue()) && !source.getValue().contains("${")) {
                    sourceCompatibility = source.getValue();
                }
                Xpp3Dom target = dom.getChild("testTarget");
                if (target != null && StringUtils.isNotEmpty(target.getValue()) && !target.getValue().contains("${")) {
                    targetCompatibility = target.getValue();
                }
            }
        }

        if (sourceCompatibility == null || targetCompatibility == null) {
            String propertiesReleaseCompatibility = (String) mavenProject.getProperties().get("maven.compiler.testRelease");
            if (propertiesReleaseCompatibility != null) {
                sourceCompatibility = propertiesReleaseCompatibility;
                targetCompatibility = propertiesReleaseCompatibility;
            } else {
                String propertiesSourceCompatibility = (String) mavenProject.getProperties().get("maven.compiler.testSource");
                if (sourceCompatibility == null && propertiesSourceCompatibility != null) {
                    sourceCompatibility = propertiesSourceCompatibility;
                }
                String propertiesTargetCompatibility = (String) mavenProject.getProperties().get("maven.compiler.testTarget");
                if (targetCompatibility == null && propertiesTargetCompatibility != null) {
                    targetCompatibility = propertiesTargetCompatibility;
                }
            }
        }

        // Fall back to main source compatibility if test source compatibility is not set, or only partially set.
        if (sourceCompatibility == null || targetCompatibility == null) {
            JavaVersion srcMainJavaVersion = getSrcMainJavaVersion(mavenProject);
            if (sourceCompatibility == null && targetCompatibility == null) {
                return srcMainJavaVersion;
            }
            sourceCompatibility = sourceCompatibility == null ? srcMainJavaVersion.getSourceCompatibility() : sourceCompatibility;
            targetCompatibility = targetCompatibility == null ? srcMainJavaVersion.getTargetCompatibility() : targetCompatibility;
        }

        return getJavaVersionMarker(sourceCompatibility, targetCompatibility);
    }

    private static JavaVersion getJavaVersionMarker(@Nullable String sourceCompatibility, @Nullable String targetCompatibility) {
        String javaRuntimeVersion = System.getProperty("java.specification.version");
        String javaVendor = System.getProperty("java.vm.vendor");
        if (sourceCompatibility == null) {
            sourceCompatibility = javaRuntimeVersion;
        }
        if (targetCompatibility == null) {
            targetCompatibility = sourceCompatibility;
        }
        return new JavaVersion(randomId(), javaRuntimeVersion, javaVendor, sourceCompatibility, targetCompatibility);
    }

    private Stream<SourceFile> processMainSources(
            MavenProject mavenProject,
            JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder,
            KotlinParser.Builder kotlinParserBuilder,
            ResourceParser resourceParser,
            List<Marker> projectProvenance,
            Set<Path> alreadyParsed,
            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

        // Some annotation processors output generated sources to the /target directory. These are added for parsing but
        // should be filtered out of the final SourceFile list.
        List<Path> generatedSourcePaths = listJavaSources(mavenProject.getBasedir().toPath().resolve(mavenProject.getBuild().getDirectory()));
        List<Path> mainJavaSources = Stream.concat(
                generatedSourcePaths.stream(),
                listJavaSources(mavenProject.getBasedir().toPath().resolve(mavenProject.getBuild().getSourceDirectory())).stream()
        ).collect(toList());

        alreadyParsed.addAll(mainJavaSources);

        // scan Kotlin files
        String kotlinSourceDir = getKotlinDirectory(mavenProject.getBuild().getSourceDirectory());
        List<Path> mainKotlinSources = (kotlinSourceDir != null) ? listKotlinSources(mavenProject.getBasedir().toPath().resolve(kotlinSourceDir)) : Collections.emptyList();
        alreadyParsed.addAll(mainKotlinSources);

        logInfo(mavenProject, "Parsing source files");
        List<Path> dependencies = mavenProject.getCompileClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());
        JavaTypeCache typeCache = new JavaTypeCache();
        javaParserBuilder.classpath(dependencies).typeCache(typeCache);
        kotlinParserBuilder.classpath(dependencies).typeCache(new JavaTypeCache());

        Stream<SourceFile> parsedJava = Stream.empty();
        if (!mainJavaSources.isEmpty()) {
            parsedJava = javaParserBuilder.build().parse(mainJavaSources, baseDir, ctx);
            logDebug(mavenProject, "Scanned " + mainJavaSources.size() + " java source files in main scope.");
        }

        Stream<SourceFile> parsedKotlin = Stream.empty();
        if (!mainKotlinSources.isEmpty()) {
            parsedKotlin = kotlinParserBuilder.build().parse(mainKotlinSources, baseDir, ctx);
            logDebug(mavenProject, "Scanned " + mainKotlinSources.size() + " kotlin source files in main scope.");
        }

        List<Marker> mainProjectProvenance = new ArrayList<>(projectProvenance);
        mainProjectProvenance.add(sourceSet("main", dependencies, typeCache));
        mainProjectProvenance.add(getSrcMainJavaVersion(mavenProject));

        //Filter out any generated source files from the returned list, as we do not want to apply the recipe to the
        //generated files.
        Path buildDirectory = baseDir.relativize(Paths.get(mavenProject.getBuild().getDirectory()));
        Stream<SourceFile> sourceFiles = Stream.concat(parsedJava, parsedKotlin)
                .filter(s -> !s.getSourcePath().startsWith(buildDirectory))
                .map(addProvenance(baseDir, mainProjectProvenance, generatedSourcePaths));

        // Any resources parsed from "main/resources" should also have the main source set added to them.
        int sourcesParsedBefore = alreadyParsed.size();
        Stream<SourceFile> parsedResourceFiles = resourceParser.parse(mavenProject.getBasedir().toPath().resolve("src/main/resources"), alreadyParsed)
                .map(addProvenance(baseDir, mainProjectProvenance, null));
        logDebug(mavenProject, "Scanned " + (alreadyParsed.size() - sourcesParsedBefore) + " resource files in main scope.");
        return Stream.concat(sourceFiles, parsedResourceFiles);
    }

    private Stream<SourceFile> processTestSources(
            MavenProject mavenProject,
            JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder,
            KotlinParser.Builder kotlinParserBuilder,
            ResourceParser resourceParser,
            List<Marker> projectProvenance,
            Set<Path> alreadyParsed,
            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

        List<Path> testDependencies = mavenProject.getTestClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());

        JavaTypeCache typeCache = new JavaTypeCache();
        javaParserBuilder.classpath(testDependencies).typeCache(typeCache);
        kotlinParserBuilder.classpath(testDependencies).typeCache(new JavaTypeCache());

        List<Path> testJavaSources = listJavaSources(mavenProject.getBasedir().toPath().resolve(mavenProject.getBuild().getTestSourceDirectory()));
        alreadyParsed.addAll(testJavaSources);

        // scan Kotlin files
        String kotlinSourceDir = getKotlinDirectory(mavenProject.getBuild().getTestSourceDirectory());
        List<Path> testKotlinSources = (kotlinSourceDir != null) ? listKotlinSources(mavenProject.getBasedir().toPath().resolve(kotlinSourceDir)) : Collections.emptyList();
        alreadyParsed.addAll(testKotlinSources);

        Stream<SourceFile> parsedJava = Stream.empty();
        if (!testJavaSources.isEmpty()) {
            parsedJava = javaParserBuilder.build().parse(testJavaSources, baseDir, ctx);
            logDebug(mavenProject, "Scanned " + testJavaSources.size() + " java source files in test scope.");
        }

        Stream<SourceFile> parsedKotlin = Stream.empty();
        if (!testKotlinSources.isEmpty()) {
            parsedKotlin = kotlinParserBuilder.build().parse(testKotlinSources, baseDir, ctx);
            logDebug(mavenProject, "Scanned " + testKotlinSources.size() + " kotlin source files in main scope.");
        }

        List<Marker> markers = new ArrayList<>(projectProvenance);
        markers.add(sourceSet("test", testDependencies, typeCache));
        markers.add(getSrcTestJavaVersion(mavenProject));

        // Any resources parsed from "test/resources" should also have the test source set added to them.
        int sourcesParsedBefore = alreadyParsed.size();
        Stream<SourceFile> parsedResourceFiles = resourceParser.parse(mavenProject.getBasedir().toPath().resolve("src/test/resources"), alreadyParsed);
        logDebug(mavenProject, "Scanned " + (alreadyParsed.size() - sourcesParsedBefore) + " resource files in test scope.");
        return Stream.concat(Stream.concat(parsedJava, parsedKotlin), parsedResourceFiles)
                .map(addProvenance(baseDir, markers, null));
    }

    @Nullable
    private String getKotlinDirectory(@Nullable String sourceDirectory) {
        if (sourceDirectory == null) {
            return null;
        }

        File directory = new File(sourceDirectory);
        File parentDirectory = directory.getParentFile();
        if (parentDirectory != null) {
            File[] subdirectories = parentDirectory.listFiles(File::isDirectory);
            if (subdirectories != null) {
                for (File subdirectory : subdirectories) {
                    if (subdirectory.getName().equals("kotlin")) {
                        return subdirectory.getAbsolutePath();
                    }
                }
            }
        }

        return null;
    }

    private static JavaSourceSet sourceSet(String name, List<Path> dependencies, JavaTypeCache typeCache) {
        return JavaSourceSet.build(name, dependencies, typeCache, false);
    }

    @Nullable
    public Xml.Document parseMaven(MavenProject mavenProject, List<Marker> projectProvenance, ExecutionContext ctx) {
        return parseMaven(singletonList(mavenProject), singletonMap(mavenProject, projectProvenance), ctx).get(mavenProject);
    }

    public Map<MavenProject, Xml.Document> parseMaven(List<MavenProject> mavenProjects, Map<MavenProject, List<Marker>> projectProvenances, ExecutionContext ctx) {
        if (skipMavenParsing) {
            logger.info("Skipping Maven parsing...");
            return emptyMap();
        }

        MavenProject topLevelProject = mavenSession.getTopLevelProject();
        logInfo(topLevelProject, "Resolving Poms...");

        Set<Path> allPoms = new LinkedHashSet<>();
        mavenProjects.forEach(p -> collectPoms(p, allPoms));
        for (MavenProject mavenProject : mavenProjects) {
            mavenSession.getProjectDependencyGraph().getUpstreamProjects(mavenProject, true).forEach(p -> collectPoms(p, allPoms));
        }
        MavenParser.Builder mavenParserBuilder = MavenParser.builder().mavenConfig(baseDir.resolve(MVN_MAVEN_CONFIG));

        MavenSettings settings = buildSettings();
        MavenExecutionContextView mavenExecutionContext = MavenExecutionContextView.view(ctx);
        mavenExecutionContext.setMavenSettings(settings);
        mavenExecutionContext.setResolutionListener(new MavenLoggingResolutionEventListener(logger));

        if (pomCacheEnabled) {
            //The default pom cache is enabled as a two-layer cache L1 == in-memory and L2 == RocksDb
            //If the flag is set to false, only the default, in-memory cache is used.
            mavenExecutionContext.setPomCache(getPomCache(pomCacheDirectory, logger));
        }

        List<String> activeProfiles = topLevelProject.getActiveProfiles().stream().map(Profile::getId).collect(toList());
        if (!activeProfiles.isEmpty()) {
            mavenParserBuilder.activeProfiles(activeProfiles.toArray(new String[]{}));
        }

        List<SourceFile> mavens = mavenParserBuilder.build()
                .parse(allPoms, baseDir, ctx)
                .collect(toList());

        if (logger.isDebugEnabled()) {
            logDebug(topLevelProject, "Base directory : '" + baseDir + "'");
            if (allPoms.isEmpty()) {
                logDebug(topLevelProject, "There were no collected pom paths.");
            } else {
                for (Path path : allPoms) {
                    logDebug(topLevelProject, "  Collected Maven POM : '" + path + "'");
                }
            }
            if (mavens.isEmpty()) {
                logDebug(topLevelProject, "There were no parsed maven source files.");
            } else {
                for (SourceFile source : mavens) {
                    logDebug(topLevelProject, "  Maven Source : '" + baseDir.resolve(source.getSourcePath()) + "'");
                }
            }
        }

        Map<Path, MavenProject> projectsByPath = mavenProjects.stream().collect(Collectors.toMap(MavenMojoProjectParser::pomPath, Function.identity()));
        Map<MavenProject, Xml.Document> projectMap = new HashMap<>();
        for (SourceFile document : mavens) {
            Path path = baseDir.resolve(document.getSourcePath());
            MavenProject mavenProject = projectsByPath.get(path);
            if (mavenProject != null) {
                if (document instanceof Xml.Document) {
                    projectMap.put(mavenProject, (Xml.Document) document);
                } else if (document instanceof ParseError) {
                    logError(mavenProject, "Parse error in Maven Project File '" + path + "': " + document);
                }
            }
        }
        for (MavenProject mavenProject : mavenProjects) {
            if (projectMap.get(mavenProject) == null) {
                logError(mavenProject, "Parse resulted in no Maven source files. Maven Project File '" + mavenProject.getFile().toPath() + "'");
                return emptyMap();
            }
        }

        // assign provenance markers
        for (MavenProject mavenProject : mavenProjects) {
            Xml.Document document = projectMap.get(mavenProject);
            List<Marker> provenance = projectProvenances.getOrDefault(mavenProject, emptyList());
            Markers markers = document.getMarkers();
            for (Marker marker : provenance) {
                markers = markers.addIfAbsent(marker);
            }
            projectMap.put(mavenProject, document.withMarkers(markers));
        }

        return projectMap;
    }

    /**
     * Recursively navigate the maven project to collect any poms that are local (on disk)
     *
     * @param project A maven project to examine for any children/parent poms.
     * @param paths   A list of paths to poms that have been collected so far.
     */
    private void collectPoms(MavenProject project, Set<Path> paths) {
        paths.add(pomPath(project));

        // children
        if (project.getCollectedProjects() != null) {
            for (MavenProject child : project.getCollectedProjects()) {
                Path path = pomPath(child);
                if (!paths.contains(path)) {
                    collectPoms(child, paths);
                }
            }
        }

        MavenProject parent = project.getParent();
        while (parent != null && parent.getFile() != null) {
            Path path = pomPath(parent);
            if (!paths.contains(path)) {
                collectPoms(parent, paths);
            }
            parent = parent.getParent();
        }
    }

    private static Path pomPath(MavenProject mavenProject) {
        Path pomPath = mavenProject.getFile().toPath();
        // org.codehaus.mojo:flatten-maven-plugin produces a synthetic pom unsuitable for our purposes, use the regular pom instead
        if (pomPath.endsWith(".flattened-pom.xml")) {
            return mavenProject.getBasedir().toPath().resolve("pom.xml");
        }
        // org.eclipse.tycho:tycho-packaging-plugin:update-consumer-pom produces a synthetic pom
        if (pomPath.endsWith(".tycho-consumer-pom.xml")) {
            Path normalPom = mavenProject.getBasedir().toPath().resolve("pom.xml");
            // check for existence of the POM, since Tycho can work pom-less
            if (Files.isReadable(normalPom) && Files.isRegularFile(normalPom)) {
                return normalPom;
            }
        }
        return pomPath;
    }

    private static MavenPomCache getPomCache(@Nullable String pomCacheDirectory, Log logger) {
        if (POM_CACHE == null) {
            POM_CACHE = new MavenPomCacheBuilder(logger).build(pomCacheDirectory);
        }
        if (POM_CACHE == null) {
            POM_CACHE = new InMemoryMavenPomCache();
        }
        return POM_CACHE;
    }

    public MavenSettings buildSettings() {
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
                    decryptionResult.getServer().getPassword(),
                    null
            );
        }).collect(toList()));

        return new MavenSettings(mer.getLocalRepositoryPath().toString(), profiles, activeProfiles, mirrors, servers);
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
                r.getReleases() == null ? null : new RawRepositories.ArtifactPolicy(Boolean.toString(r.getReleases().isEnabled())),
                r.getSnapshots() == null ? null : new RawRepositories.ArtifactPolicy(Boolean.toString(r.getSnapshots().isEnabled()))
        )).collect(toList());
        rawRepositories.setRepositories(transformedRepositories);
        return rawRepositories;
    }

    /**
     * Used to scope `Files.walkFileTree` to the current maven project by skipping the subtrees of other MavenProjects.
     */
    private Set<Path> pathsToOtherMavenProjects(MavenProject mavenProject) {
        return mavenSession.getProjects().stream()
                .filter(o -> o != mavenProject)
                .map(o -> o.getBasedir().toPath())
                .collect(Collectors.toSet());
    }

    private <T extends SourceFile> UnaryOperator<T> addProvenance(Path baseDir, List<Marker> provenance, @Nullable Collection<Path> generatedSources) {
        return s -> {
            Markers markers = s.getMarkers();
            for (Marker marker : provenance) {
                markers = markers.addIfAbsent(marker);
            }
            if (generatedSources != null && generatedSources.contains(baseDir.resolve(s.getSourcePath()))) {
                markers = markers.addIfAbsent(new Generated(randomId()));
            }
            return s.withMarkers(markers);
        };
    }

    private static List<Path> listJavaSources(Path sourceDirectory) throws MojoExecutionException {
        return listSources(sourceDirectory, ".java");
    }

    private static List<Path> listKotlinSources(Path sourceDirectory) throws MojoExecutionException {
        return listSources(sourceDirectory, ".kt");
    }

    private static List<Path> listSources(Path sourceDirectory, String extension) throws MojoExecutionException {
        if (!Files.exists(sourceDirectory)) {
            return emptyList();
        }

        try (Stream<Path> files = Files.find(sourceDirectory, 16, (f, a) -> !a.isDirectory() && f.toString().endsWith(extension))) {
            return files.collect(toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list source files of " + extension, e);
        }
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

    private void logError(MavenProject mavenProject, String message) {
        logger.error("Project [" + mavenProject.getName() + "] " + message);
    }

    private void logInfo(MavenProject mavenProject, String message) {
        logger.info("Project [" + mavenProject.getName() + "] " + message);
    }

    private void logDebug(MavenProject mavenProject, String message) {
        logger.debug("Project [" + mavenProject.getName() + "] " + message);
    }

    @SuppressWarnings({"RedundantThrows", "unchecked"})
    private static <E extends Throwable> E sneakyThrow(Throwable e) throws E {
        return (E) e;
    }
}
