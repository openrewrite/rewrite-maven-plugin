/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Repository;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ParseExceptionResult;
import org.openrewrite.PathUtils;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.internal.JavaTypeCache;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.marker.JavaVersion;
import org.openrewrite.jgit.api.Git;
import org.openrewrite.jgit.lib.FileMode;
import org.openrewrite.jgit.lib.ObjectId;
import org.openrewrite.jgit.revwalk.RevCommit;
import org.openrewrite.jgit.revwalk.RevWalk;
import org.openrewrite.jgit.treewalk.FileTreeIterator;
import org.openrewrite.jgit.treewalk.TreeWalk;
import org.openrewrite.jgit.treewalk.WorkingTreeIterator;
import org.openrewrite.jgit.treewalk.filter.PathFilter;
import org.openrewrite.jgit.treewalk.filter.PathFilterGroup;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.marker.*;
import org.openrewrite.marker.ci.BuildEnvironment;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.MavenPomCache;
import org.openrewrite.maven.internal.MavenXmlMapper;
import org.openrewrite.maven.internal.RawPom;
import org.openrewrite.maven.internal.RawRepositories;
import org.openrewrite.maven.tree.Pom;
import org.openrewrite.maven.tree.ProfileActivation;
import org.openrewrite.maven.tree.ResolvedGroupArtifactVersion;
import org.openrewrite.maven.utilities.MavenWrapper;
import org.openrewrite.polyglot.OmniParser;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.xml.tree.Xml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.openrewrite.PathUtils.separatorsToUnix;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.maven.MavenMojoProjectParser.MavenScope.MAIN;
import static org.openrewrite.maven.MavenMojoProjectParser.MavenScope.TEST;
import static org.openrewrite.tree.ParsingExecutionContextView.view;

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

    private static final String MVN_JVM_CONFIG = ".mvn/jvm.config";
    private static final String MVN_MAVEN_CONFIG = ".mvn/maven.config";
    private static final String MAVEN_COMPILER_PLUGIN = "org.apache.maven.plugins:maven-compiler-plugin";

    @Nullable
    public static MavenPomCache POM_CACHE;

    private final Log logger;
    private final AtomicBoolean firstWarningLogged = new AtomicBoolean(false);
    private final Path baseDir;
    private final org.openrewrite.jgit.lib.@Nullable Repository repository;
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
        this.repository = getRepository(baseDir);
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
                                              ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException, MojoFailureException {
        if (runPerSubmodule) {
            //If running per submodule, parse the source files for only the current project.
            List<Marker> projectProvenance = generateProvenance(mavenProject);
            Xml.Document maven = parseMaven(mavenProject, projectProvenance, ctx);
            return listSourceFiles(mavenProject, maven, projectProvenance, styles, ctx);
        }
        //If running across all projects, iterate and parse source files from each project
        Map<MavenProject, List<Marker>> projectProvenances = mavenSession.getProjects().stream()
          .collect(toMap(Function.identity(), this::generateProvenance));
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

    public Stream<SourceFile> listSourceFiles(MavenProject mavenProject, Xml.@Nullable Document maven, List<Marker> projectProvenance, List<NamedStyles> styles,
                                              ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        return listSourceFiles(mavenProject, maven, projectProvenance, Arrays.asList(MAIN, TEST),  styles, ctx);
    }

    public Stream<SourceFile> listSourceFiles(MavenProject mavenProject, Xml.@Nullable Document maven, List<Marker> projectProvenance, List<MavenScope> scopes,
                List<NamedStyles> styles, ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        Stream<SourceFile> sourceFiles = Stream.empty();
        Set<Path> alreadyParsed = new HashSet<>();

        if (maven != null) {
            sourceFiles = Stream.of(maven);
            alreadyParsed.add(baseDir.resolve(maven.getSourcePath()));
        }

        JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder = JavaParser.fromJavaVersion()
                .styles(styles)
                .logCompilationWarningsAndErrors(false);

        // todo, add styles from autoDetect
        KotlinParser.Builder kotlinParserBuilder = KotlinParser.builder();

        if (scopes.contains(MAIN)) {
            sourceFiles = Stream.concat(sourceFiles, processMainSources(mavenProject, javaParserBuilder.clone(), kotlinParserBuilder.clone(), alreadyParsed, ctx));
        }
        if (scopes.contains(TEST)) {
            sourceFiles = Stream.concat(sourceFiles, processTestSources(mavenProject, javaParserBuilder.clone(), kotlinParserBuilder.clone(), alreadyParsed, ctx));
        }
        Collection<PathMatcher> exclusionMatchers = exclusions.stream()
                .map(pattern -> baseDir.getFileSystem().getPathMatcher("glob:" + pattern))
                .collect(toList());
        sourceFiles = sourceFiles.map(sourceFile -> {
            if (isExcluded(exclusionMatchers, sourceFile.getSourcePath())) {
                return null;
            }
            return sourceFile;
        }).filter(Objects::nonNull);

        Stream<SourceFile> mavenWrapperFiles = parseMavenWrapperFiles(mavenProject, exclusionMatchers, alreadyParsed, ctx);
        sourceFiles = Stream.concat(sourceFiles, mavenWrapperFiles);

        Stream<SourceFile> nonProjectResources = parseNonProjectResources(mavenProject, alreadyParsed, ctx);
        sourceFiles = Stream.concat(sourceFiles, nonProjectResources);

        return sourceFiles.map(addProvenance(projectProvenance))
                .map(addGitObjectId())
                .map(this::logParseErrors);
    }

    private boolean isExcluded(Collection<PathMatcher> exclusionMatchers, Path path) {
        for (PathMatcher excluded : exclusionMatchers) {
            if (excluded.matches(path)) {
                return true;
            }
        }
        // PathMather will not evaluate the path "pom.xml" to be matched by the pattern "**/pom.xml"
        // This is counter-intuitive for most users and would otherwise require separate exclusions for files at the root and files in subdirectories
        if (!path.isAbsolute() && !path.startsWith(File.separator)) {
            return isExcluded(exclusionMatchers, Paths.get("/" + path));
        }

        if (repository != null) {
            String repoRelativePath = separatorsToUnix(path.toString());
            if (repoRelativePath.isEmpty() && "/".equals(repoRelativePath)) {
                return false;
            }

            try (TreeWalk walk = new TreeWalk(repository)) {
                walk.addTree(new FileTreeIterator(repository));
                walk.setFilter(PathFilterGroup.createFromStrings(repoRelativePath));
                while (walk.next()) {
                    WorkingTreeIterator workingTreeIterator = walk.getTree(0, WorkingTreeIterator.class);
                    if (walk.getPathString().equals(repoRelativePath)) {
                        return workingTreeIterator.isEntryIgnored();
                    }
                    if (workingTreeIterator.getEntryFileMode().equals(FileMode.TREE)) {
                        if (workingTreeIterator.isEntryIgnored()) {
                            return true;
                        }
                        walk.enterSubtree();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return false;
    }

    public enum MavenScope {
        MAIN,
        TEST
    }

    private static Optional<Charset> getCharset(MavenProject mavenProject) {
        String compilerPluginKey = MAVEN_COMPILER_PLUGIN;
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

    static org.openrewrite.jgit.lib.@Nullable Repository getRepository(Path rootDir) {
        try (Git git = Git.open(rootDir.toFile())) {
            return git.getRepository();
        } catch (IOException e) {
            // no git
            return null;
        }
    }

    private SourceFile logParseErrors(SourceFile source) {
        source.getMarkers().findFirst(ParseExceptionResult.class).ifPresent(e -> {
            if (firstWarningLogged.compareAndSet(false, true)) {
                logger.warn("There were problems parsing some source files" +
                        (mavenSession.getRequest().isShowErrors() ? "" : ", run with --errors to see full stack traces"));
            }
            logger.warn("There were problems parsing " + source.getSourcePath());
            if (mavenSession.getRequest().isShowErrors()) {
                logger.warn(e.getMessage());
            }
        });
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

        Plugin compilerPlugin = mavenProject.getPlugin(MAVEN_COMPILER_PLUGIN);
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

        Plugin compilerPlugin = mavenProject.getPlugin(MAVEN_COMPILER_PLUGIN);
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
            Set<Path> alreadyParsed,
            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

        Stream<SourceFile> sourceFiles = Stream.of();

        // scan Java files
        Collection<Path> mainJavaSources = listJavaSources(mavenProject, mavenProject.getExecutionProject().getCompileSourceRoots());
        alreadyParsed.addAll(mainJavaSources);

        // scan Kotlin files
        List<Path> mainKotlinSources = listKotlinSources(mavenProject, "compile", mavenProject.getBuild().getSourceDirectory());
        alreadyParsed.addAll(mainKotlinSources);

        logInfo(mavenProject, "Parsing source files");
        List<Path> dependencies = mavenProject.getCompileClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());
        JavaTypeCache typeCache = new JavaTypeCache();
        javaParserBuilder.classpath(dependencies).typeCache(typeCache);
        kotlinParserBuilder.classpath(dependencies).typeCache(typeCache);

        if (!mainJavaSources.isEmpty()) {
            Stream<SourceFile> parsedJava = Stream.of((Supplier<JavaParser>) javaParserBuilder::build)
                    .map(Supplier::get)
                    .flatMap(jp -> {
                        view(ctx).setCharset(getCharset(mavenProject).orElse(null));
                        return jp.parse(mainJavaSources, baseDir, ctx).onClose(() -> view(ctx).setCharset(null));
                    });
            sourceFiles = Stream.concat(sourceFiles, parsedJava);
            logDebug(mavenProject, "Scanned " + mainJavaSources.size() + " java source files in main scope.");
        }

        if (!mainKotlinSources.isEmpty()) {
            Stream<SourceFile> parsedKotlin = Stream.of((Supplier<KotlinParser>) kotlinParserBuilder::build)
                    .map(Supplier::get)
                    .flatMap(kp -> {
                        view(ctx).setCharset(StandardCharsets.UTF_8); // Kotlin requires UTF-8
                        return kp.parse(mainKotlinSources, baseDir, ctx).onClose(() -> view(ctx).setCharset(null));
                    });
            sourceFiles = Stream.concat(sourceFiles, parsedKotlin);
            logDebug(mavenProject, "Scanned " + mainKotlinSources.size() + " kotlin source files in main scope.");
        }

        OmniParser omniParser = omniParser(alreadyParsed, mavenProject);
        for (Resource resource : mavenProject.getResources()) {
            Path resourcePath = mavenProject.getBasedir().toPath().resolve(resource.getDirectory());
            if (Files.exists(resourcePath) && !alreadyParsed.contains(resourcePath)) {
                List<Path> accepted = omniParser.acceptedPaths(baseDir, resourcePath);
                alreadyParsed.add(resourcePath);
                sourceFiles = Stream.concat(sourceFiles, omniParser.parse(accepted, baseDir, ctx));
                alreadyParsed.addAll(accepted);
            }
        }

        List<Marker> mainProjectProvenance = new ArrayList<>();
        mainProjectProvenance.add(JavaSourceSet.build("main", dependencies));
        mainProjectProvenance.add(getSrcMainJavaVersion(mavenProject));

        //Filter out any generated source files from the returned list, as we do not want to apply the recipe to the
        //generated files.
        Path buildDirectory = baseDir.relativize(Paths.get(mavenProject.getBuild().getDirectory()));
        return sourceFiles
                .filter(s -> !s.getSourcePath().startsWith(buildDirectory))
                .map(addProvenance(mainProjectProvenance));
    }

    private Stream<SourceFile> processTestSources(
            MavenProject mavenProject,
            JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder,
            KotlinParser.Builder kotlinParserBuilder,
            Set<Path> alreadyParsed,
            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {

        Stream<SourceFile> sourceFiles = Stream.of();

        // scan Java files
        Collection<Path> testJavaSources = listJavaSources(mavenProject, mavenProject.getExecutionProject().getTestCompileSourceRoots());
        alreadyParsed.addAll(testJavaSources);

        // scan Kotlin files
        List<Path> testKotlinSources = listKotlinSources(mavenProject, "test-compile", mavenProject.getBuild().getTestSourceDirectory());
        alreadyParsed.addAll(testKotlinSources);

        List<Path> testDependencies = mavenProject.getTestClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());
        JavaTypeCache typeCache = new JavaTypeCache();
        javaParserBuilder.classpath(testDependencies).typeCache(typeCache);
        kotlinParserBuilder.classpath(testDependencies).typeCache(typeCache);

        if (!testJavaSources.isEmpty()) {
            Stream<SourceFile> parsedJava = Stream.of((Supplier<JavaParser>) javaParserBuilder::build)
                    .map(Supplier::get)
                    .flatMap(jp -> {
                        view(ctx).setCharset(getCharset(mavenProject).orElse(null));
                        return jp.parse(testJavaSources, baseDir, ctx).onClose(() -> view(ctx).setCharset(null));
                    });
            sourceFiles = Stream.concat(sourceFiles, parsedJava);
            logDebug(mavenProject, "Scanned " + testJavaSources.size() + " java source files in test scope.");
        }

        if (!testKotlinSources.isEmpty()) {
            Stream<SourceFile> parsedKotlin = Stream.of((Supplier<KotlinParser>) kotlinParserBuilder::build)
                    .map(Supplier::get)
                    .flatMap(kp -> {
                        view(ctx).setCharset(StandardCharsets.UTF_8); // Kotlin requires UTF-8
                        return kp.parse(testKotlinSources, baseDir, ctx).onClose(() -> view(ctx).setCharset(null));
                    });
            sourceFiles = Stream.concat(sourceFiles, parsedKotlin);
            logDebug(mavenProject, "Scanned " + testKotlinSources.size() + " kotlin source files in test scope.");
        }

        OmniParser omniParser = omniParser(alreadyParsed, mavenProject);
        for (Resource resource : mavenProject.getTestResources()) {
            Path resourcePath = mavenProject.getBasedir().toPath().resolve(resource.getDirectory());
            if (Files.exists(resourcePath) && !alreadyParsed.contains(resourcePath)) {
                List<Path> accepted = omniParser.acceptedPaths(baseDir, resourcePath);
                alreadyParsed.add(resourcePath);
                sourceFiles = Stream.concat(sourceFiles, omniParser.parse(accepted, baseDir, ctx));
                alreadyParsed.addAll(accepted);
            }
        }

        List<Marker> testProjectProvenance = new ArrayList<>();
        testProjectProvenance.add(JavaSourceSet.build("test", testDependencies));
        testProjectProvenance.add(getSrcTestJavaVersion(mavenProject));

        //Filter out any generated source files from the returned list, as we do not want to apply the recipe to the
        //generated files.
        Path buildDirectory = baseDir.relativize(Paths.get(mavenProject.getBuild().getDirectory()));
        return sourceFiles
                .filter(s -> !s.getSourcePath().startsWith(buildDirectory))
                .map(addProvenance(testProjectProvenance));
    }

    public Xml.@Nullable Document parseMaven(MavenProject mavenProject, List<Marker> projectProvenance, ExecutionContext ctx) throws MojoFailureException {
        return parseMaven(singletonList(mavenProject), singletonMap(mavenProject, projectProvenance), ctx).get(mavenProject);
    }

    public Map<MavenProject, Xml.Document> parseMaven(List<MavenProject> mavenProjects, Map<MavenProject, List<Marker>> projectProvenances, ExecutionContext ctx) throws MojoFailureException {
        if (skipMavenParsing) {
            logger.info("Skipping Maven parsing...");
            return emptyMap();
        }

        MavenSettings settings = buildSettings();
        MavenExecutionContextView mavenExecutionContext = MavenExecutionContextView.view(ctx);
        mavenExecutionContext.setMavenSettings(settings);
        mavenExecutionContext.setResolutionListener(new MavenLoggingResolutionEventListener(logger));

        // The default pom cache is enabled as a two-layer cache L1 == in-memory and L2 == RocksDb
        // If the flag is set to false, only the default, in-memory cache is used.
        MavenPomCache pomCache = pomCacheEnabled ? getPomCache(pomCacheDirectory, logger) : mavenExecutionContext.getPomCache();
        mavenExecutionContext.setPomCache(pomCache);

        MavenProject topLevelProject = mavenSession.getTopLevelProject();
        logInfo(topLevelProject, "Resolving Poms...");

        Set<Path> allPoms = new LinkedHashSet<>();
        mavenProjects.forEach(p -> collectPoms(p, allPoms, mavenExecutionContext));
        for (MavenProject mavenProject : mavenProjects) {
            mavenSession.getProjectDependencyGraph().getUpstreamProjects(mavenProject, true).forEach(p -> collectPoms(p, allPoms, mavenExecutionContext));
        }

        MavenParser.Builder mavenParserBuilder = MavenParser.builder();
        mavenParserBuilder.property("basedir", topLevelProject.getBasedir().getAbsoluteFile().getParent());
        mavenParserBuilder.property("project.basedir", topLevelProject.getBasedir().getAbsoluteFile().getParent());
        topLevelProject.getActiveProfiles().forEach(it -> mavenParserBuilder.activeProfiles(it.getId()));
        mavenSession.getRequest().getActiveProfiles().forEach(mavenParserBuilder::activeProfiles);
        mavenSession.getUserProperties().forEach((key, value) ->
                mavenParserBuilder.property((String) key, (String) value));

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

        Map<Path, MavenProject> projectsByPath = mavenProjects.stream().collect(toMap(MavenMojoProjectParser::pomPath, Function.identity()));
        Map<MavenProject, Xml.Document> projectMap = new HashMap<>();
        for (SourceFile document : mavens) {
            Path path = baseDir.resolve(document.getSourcePath());
            MavenProject mavenProject = projectsByPath.get(path);
            if (mavenProject != null) {
                Optional<ParseExceptionResult> parseExceptionResult = document.getMarkers().findFirst(ParseExceptionResult.class);
                if (parseExceptionResult.isPresent()) {
                    throw new MojoFailureException(
                            mavenProject,
                            "Failed to parse or resolve the Maven POM file or one of its dependencies; " +
                                    "We can not reliably continue without this information.",
                            parseExceptionResult.get().getMessage());
                }
                projectMap.put(mavenProject, (Xml.Document) document);
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
     * @param ctx     The execution context for the current project.
     */
    private void collectPoms(MavenProject project, Set<Path> paths, MavenExecutionContextView ctx) {
        if (!paths.add(pomPath(project))) {
            return;
        }

        ResolvedGroupArtifactVersion gav = createResolvedGAV(project, ctx);
        ctx.getPomCache().putPom(gav, createPom(project));

        // children
        if (project.getCollectedProjects() != null) {
            for (MavenProject child : project.getCollectedProjects()) {
                Path path = pomPath(child);
                if (!paths.contains(path)) {
                    collectPoms(child, paths, ctx);
                }
            }
        }

        MavenProject parent = project.getParent();
        while (parent != null && parent.getFile() != null) {
            Path path = pomPath(parent);
            if (!paths.contains(path)) {
                collectPoms(parent, paths, ctx);
            }
            parent = parent.getParent();
        }
    }

    private static Path pomPath(MavenProject mavenProject) {
        Path pomPath = mavenProject.getFile().toPath();
        if (pomPath.endsWith(".flattened-pom.xml") ||// org.codehaus.mojo:flatten-maven-plugin
                pomPath.endsWith("dependency-reduced-pom.xml") || // org.apache.maven.plugins:maven-shade-plugin
                pomPath.endsWith(".ci-friendly-pom.xml") || // com.outbrain.swinfra:ci-friendly-flatten-maven-plugin
                pomPath.endsWith(".tycho-consumer-pom.xml")) { // org.eclipse.tycho:tycho-packaging-plugin:update-consumer-pom
            Path normalPom = mavenProject.getBasedir().toPath().resolve("pom.xml");
            // check for the existence of the POM, since Tycho can work pom-less
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
            MavenSettings.ServerConfiguration configuration = null;
            if (s.getConfiguration() != null) {
                try {
                    // No need to interpolate in property placeholders like ${env.Foo}, Maven has already done this
                    configuration = MavenXmlMapper.readMapper().readValue(s.getConfiguration().toString(), MavenSettings.ServerConfiguration.class);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
            return new MavenSettings.Server(
                    s.getId(),
                    s.getUsername(),
                    decryptionResult.getServer().getPassword(),
                    configuration
            );
        }).collect(toList()));

        return new MavenSettings(mer.getLocalRepositoryPath().toString(), profiles, activeProfiles, mirrors, servers);
    }

    private static @Nullable RawRepositories buildRawRepositories(@Nullable List<Repository> repositoriesToMap) {
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
                .collect(toSet());
    }

    private <T extends SourceFile> UnaryOperator<T> addProvenance(List<Marker> provenance) {
        return s -> {
            Markers markers = s.getMarkers();
            for (Marker marker : provenance) {
                markers = markers.addIfAbsent(marker);
            }
            return s.withMarkers(markers);
        };
    }

    private <T extends SourceFile> UnaryOperator<T> addGitObjectId() {
        return s -> {
            if (repository == null) {
                return s;
            }

            try {
                ObjectId head = repository.resolve("HEAD");
                if (head == null) {
                    return s;
                }

                try (RevWalk revWalk = new RevWalk(repository);
                     TreeWalk treeWalk = new TreeWalk(repository)) {
                    RevCommit commit = revWalk.parseCommit(head);
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setRecursive(true);
                    treeWalk.setFilter(PathFilter.create(PathUtils.separatorsToUnix(s.getSourcePath().toString())));

                    if (treeWalk.next()) {
                        return s.withMarkers(s.getMarkers().add(new GitObject(randomId(), treeWalk.getObjectId(0).name())));
                    }
                    return s;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static Collection<Path> listJavaSources(MavenProject mavenProject, List<String> compileSourceRoots) throws MojoExecutionException {
        Set<Path> javaSources = new LinkedHashSet<>();
        for (String compileSourceRoot : compileSourceRoots) {
            javaSources.addAll(listSources(mavenProject.getBasedir().toPath().resolve(compileSourceRoot), ".java"));
        }
        return javaSources;
    }

    private List<Path> listKotlinSources(MavenProject mavenProject, String executionId, String fallbackSourceDirectory) throws MojoExecutionException {
        Plugin kotlinPlugin = mavenProject.getPlugin("org.jetbrains.kotlin:kotlin-maven-plugin");
        if (kotlinPlugin == null) {
            return emptyList();
        }

        PluginExecution execution = kotlinPlugin.getExecutionsAsMap().get(executionId);
        if (execution != null && execution.getConfiguration() instanceof Xpp3Dom) {
            Xpp3Dom configuration = (Xpp3Dom) execution.getConfiguration();
            Xpp3Dom sourceDirs = configuration.getChild("sourceDirs");
            if (sourceDirs != null) {
                List<Path> kotlinSources = new ArrayList<>();
                for (Xpp3Dom sourceDir : sourceDirs.getChildren("sourceDir")) {
                    Path sourceDirectory = mavenProject.getBasedir().toPath().resolve(sourceDir.getValue());
                    kotlinSources.addAll(listSources(sourceDirectory, ".kt"));
                }
                return kotlinSources;
            }
        }

        return listSources(mavenProject.getBasedir().toPath().resolve(fallbackSourceDirectory), ".kt");
    }

    private static List<Path> listSources(Path sourceDirectory, String extension) throws MojoExecutionException {
        if (!Files.exists(sourceDirectory)) {
            return emptyList();
        }
        try {
            List<Path> result = new ArrayList<>();
            Files.walkFileTree(sourceDirectory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(extension)) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to list source files of " + extension, e);
        }
    }

    private Stream<SourceFile> parseMavenWrapperFiles(MavenProject mavenProject, Collection<PathMatcher> exclusions, Set<Path> alreadyParsed, ExecutionContext ctx) {
        Stream<SourceFile> sourceFiles = Stream.empty();
        if (mavenProject.getParent() == null) {
            OmniParser omniParser = omniParser(alreadyParsed, mavenProject);
            List<Path> mavenWrapperFiles = Stream.of(
                            Paths.get(MVN_JVM_CONFIG),
                            Paths.get(MVN_MAVEN_CONFIG),
                            MavenWrapper.WRAPPER_BATCH_LOCATION,
                            MavenWrapper.WRAPPER_JAR_LOCATION,
                            MavenWrapper.WRAPPER_PROPERTIES_LOCATION,
                            MavenWrapper.WRAPPER_SCRIPT_LOCATION)
                    .map(Path::toAbsolutePath)
                    .filter(Files::exists)
                    .filter(it -> !isExcluded(exclusions, it))
                    .filter(omniParser::accept)
                    .collect(toList());
            sourceFiles = omniParser.parse(mavenWrapperFiles, baseDir, ctx);
        }
        return sourceFiles;
    }

    protected Stream<SourceFile> parseNonProjectResources(MavenProject mavenProject, Set<Path> alreadyParsed, ExecutionContext ctx) {
        if (!parseAdditionalResources) {
            return Stream.empty();
        }
        //Collect any additional yaml/properties/xml files that are NOT already in a source set.
        OmniParser omniParser = omniParser(alreadyParsed, mavenProject);
        List<Path> accepted = omniParser.acceptedPaths(baseDir, mavenProject.getBasedir().toPath());
        return omniParser.parse(accepted, baseDir, ctx);
    }

    private OmniParser omniParser(Set<Path> alreadyParsed, MavenProject mavenProject) {
        return OmniParser.builder(
                        OmniParser.defaultResourceParsers(),
                        PlainTextParser.builder()
                                .plainTextMasks(baseDir, plainTextMasks)
                                .build(),
                        QuarkParser.builder().build()
                )
                .exclusionMatchers(pathMatchers(baseDir, mergeExclusions(mavenProject)))
                .exclusions(alreadyParsed)
                .sizeThresholdMb(sizeThresholdMb)
                .build();
    }

    private Collection<String> mergeExclusions(MavenProject mavenProject) {
        Path projectPath = mavenProject.getBasedir().toPath();
        return Stream.concat(
                pathsToOtherMavenProjects(mavenProject).stream()
                        .filter(otherProjectPath -> !projectPath.startsWith(otherProjectPath))
                        .map(subproject -> separatorsToUnix(baseDir.relativize(subproject).toString())),
                exclusions.stream()
        ).collect(toList());
    }

    private Collection<PathMatcher> pathMatchers(Path basePath, Collection<String> pathExpressions) {
        return pathExpressions.stream()
                .map(o -> basePath.getFileSystem().getPathMatcher("glob:" + o))
                .collect(toList());
    }

    private static final Map<Path, GitProvenance> REPO_ROOT_TO_PROVENANCE = new HashMap<>();

    private @Nullable GitProvenance gitProvenance(Path baseDir, @Nullable BuildEnvironment buildEnvironment) {
        try {
            // Computing git provenance can be expensive for repositories with many commits, ensure we do it only once
            return REPO_ROOT_TO_PROVENANCE.computeIfAbsent(baseDir, dir -> GitProvenance.fromProjectDirectory(dir, buildEnvironment));
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

    private static ResolvedGroupArtifactVersion createResolvedGAV(MavenProject project, MavenExecutionContextView ctx) {
        return new ResolvedGroupArtifactVersion(
                ctx.getLocalRepository().getUri(),
                project.getGroupId(),
                project.getArtifactId(),
                project.getVersion(),
                project.getVersion().endsWith("-SNAPSHOT") ? null : project.getVersion()
        );
    }

    private static @Nullable Pom createPom(MavenProject project) {
        Path pomPath = project.getFile().toPath();
        try (InputStream is = Files.newInputStream(pomPath)) {
            RawPom rawPom = RawPom.parse(is, null);
            return rawPom.toPom(project.getBasedir().toPath().relativize(pomPath), null);
        } catch (IOException e) {
            return null;
        }
    }
}
