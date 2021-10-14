package org.openrewrite.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.marker.GitProvenance;
import org.openrewrite.marker.Marker;
import org.openrewrite.maven.cache.InMemoryMavenPomCache;
import org.openrewrite.maven.cache.RocksdbMavenPomCache;
import org.openrewrite.maven.tree.Maven;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.PropertiesVisitor;
import org.openrewrite.style.NamedStyles;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.YamlVisitor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

public class MavenMojoProjectParser {
    private final Log logger;

    public MavenMojoProjectParser(Log logger) {
        this.logger = logger;
    }

    public List<SourceFile> listSourceFiles(MavenProject project, Path baseDir, Iterable<NamedStyles> styles,
                                            ExecutionContext ctx) throws DependencyResolutionRequiredException, MojoExecutionException {
        JavaParser javaParser = JavaParser.fromJavaVersion()
                .relaxedClassTypeMatching(true)
                .styles(styles)
                .logCompilationWarningsAndErrors(false)
                .build();

        logger.info("Parsing Java main files...");
        List<SourceFile> mainJavaSourceFiles = new ArrayList<>(512);
        List<Path> dependencies = project.getCompileClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());

        javaParser.setClasspath(dependencies);
        mainJavaSourceFiles.addAll(javaParser.parse(listJavaSources(project.getBuild().getSourceDirectory()), baseDir, ctx));

        Set<Path> mainResources = new HashSet<>(512);
        for (Resource resource : project.getBuild().getResources()) {
            addToResources(ctx, mainResources, resource);
        }

        //Add provenance information to main source files
        List<Marker> projectProvenance = getJavaProvenance();
        JavaSourceSet mainProvenance = JavaSourceSet.build("main", dependencies, ctx);
        List<SourceFile> sourceFiles = new ArrayList<>(
                ListUtils.map(mainJavaSourceFiles, addProvenance(projectProvenance, mainProvenance))
        );

        logger.info("Parsing Java test files...");
        List<SourceFile> testJavaSourceFiles = new ArrayList<>(512);
        List<Path> testDependencies = project.getTestClasspathElements().stream()
                .distinct()
                .map(Paths::get)
                .collect(toList());

        javaParser.setClasspath(testDependencies);
        testJavaSourceFiles.addAll(javaParser.parse(listJavaSources(project.getBuild().getTestSourceDirectory()), baseDir, ctx));

        Set<Path> testResources = new HashSet<>(512);
        for (Resource resource : project.getBuild().getTestResources()) {
            addToResources(ctx, testResources, resource);
        }

        JavaSourceSet testProvenance = JavaSourceSet.build("test", dependencies, ctx);
        sourceFiles.addAll(
                ListUtils.map(testJavaSourceFiles, addProvenance(projectProvenance, testProvenance))
        );

        List<SourceFile> otherMainSourceFiles = new ArrayList<>(512);
        List<SourceFile> otherTestSourceFiles = new ArrayList<>(512);

        Set<Class<?>> recipeTypes = new HashSet<>();
        discoverRecipeTypes(recipe, recipeTypes);

        if (recipeTypes.contains(YamlVisitor.class)) {
            logger.info("Parsing YAML files...");
            YamlParser yamlParser = new YamlParser();
            otherMainSourceFiles.addAll(
                    yamlParser.parse(
                            mainResources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".yml") || it.getFileName().toString().endsWith(".yaml"))
                                    .collect(toList()),
                            baseDir,
                            ctx
                    )
            );
            otherTestSourceFiles.addAll(
                    yamlParser.parse(
                            testResources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".yml") || it.getFileName().toString().endsWith(".yaml"))
                                    .collect(toList()),
                            baseDir,
                            ctx
                    )
            );
        } else {
            logger.info("Skipping YAML files because there are no active YAML recipes.");
        }

        if (recipeTypes.contains(PropertiesVisitor.class)) {
            logger.info("Parsing properties files...");
            PropertiesParser propertiesParser = new PropertiesParser();
            otherMainSourceFiles.addAll(
                    propertiesParser.parse(
                            mainResources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".properties"))
                                    .collect(toList()),
                            baseDir,
                            ctx
                    )
            );
            otherTestSourceFiles.addAll(
                    propertiesParser.parse(
                            testResources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".properties"))
                                    .collect(toList()),
                            baseDir,
                            ctx
                    )
            );
        } else {
            logger.info("Skipping properties files because there are no active properties recipes.");
        }

        if (recipeTypes.contains(XmlVisitor.class)) {
            logger.info("Parsing XML files...");
            XmlParser xmlParser = new XmlParser();
            otherMainSourceFiles.addAll(
                    xmlParser.parse(
                            mainResources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".xml"))
                                    .collect(toList()),
                            baseDir,
                            ctx
                    )
            );
            otherTestSourceFiles.addAll(
                    xmlParser.parse(
                            testResources.stream()
                                    .filter(it -> it.getFileName().toString().endsWith(".xml"))
                                    .collect(toList()),
                            baseDir,
                            ctx
                    )
            );
        } else {
            logger.info("Skipping XML files because there are no active XML recipes.");
        }

        sourceFiles.addAll(
                ListUtils.map(otherMainSourceFiles, addProvenance(projectProvenance, mainProvenance))
        );

        sourceFiles.addAll(
                ListUtils.map(otherTestSourceFiles, addProvenance(projectProvenance, testProvenance))
        );

        if (recipeTypes.contains(MavenVisitor.class)) {
            logger.info("Parsing POM...");
            Maven pomAst = parseMaven(project, baseDir, ctx);
            sourceFiles.add(
                    pomAst.withMarkers(
                            pomAst.getMarkers().withMarkers(ListUtils.concatAll(pomAst.getMarkers().getMarkers(), projectProvenance))
                    )
            );
        } else {
            logger.info("Skipping Maven POM files because there are no active Maven recipes.");
        }

        GitProvenance gitProvenance = GitProvenance.fromProjectDirectory(baseDir);
        return sourceFiles.stream()
                .map(sourceFile -> (SourceFile) sourceFile.withMarkers(sourceFile.getMarkers().add(gitProvenance)))
                .collect(Collectors.toList());
    }

    private <S extends SourceFile> UnaryOperator<S> addProvenance(List<Marker> projectProvenance, JavaSourceSet sourceSet) {
        return s -> {
            for (Marker marker : projectProvenance) {
                s = s.withMarkers(s.getMarkers().addIfAbsent(marker));
            }
            s = s.withMarkers(s.getMarkers().addIfAbsent(sourceSet));
            return s;
        };
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

    protected Maven parseMaven(MavenProject project, Path baseDir, ExecutionContext ctx) {
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
}
