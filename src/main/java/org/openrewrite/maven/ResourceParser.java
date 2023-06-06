package org.openrewrite.maven;

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.java.JavaParser;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.protobuf.ProtoParser;
import org.openrewrite.python.PythonParser;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.tree.ParsingExecutionContextView;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResourceParser {
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = new HashSet<>(Arrays.asList("build", "target", "out", ".sonar", ".gradle", ".idea", ".project", "node_modules", ".git", ".metadata", ".DS_Store"));

    private final Path baseDir;
    private final Log logger;
    private final Collection<PathMatcher> exclusions;
    private final int sizeThresholdMb;
    private final Collection<Path> excludedDirectories;
    private final Collection<PathMatcher> plainTextMasks;

    /**
     * Sometimes java files will exist in the src/main/resources directory. For example, Drools:
     */
    private final JavaParser javaParser;

    public ResourceParser(Path baseDir, Log logger, Collection<String> exclusions, Collection<String> plainTextMasks, int sizeThresholdMb, Collection<Path> excludedDirectories,
                          JavaParser javaParser) {
        this.baseDir = baseDir;
        this.logger = logger;
        this.javaParser = javaParser;
        this.exclusions = pathMatchers(baseDir, exclusions);
        this.sizeThresholdMb = sizeThresholdMb;
        this.excludedDirectories = excludedDirectories;
        this.plainTextMasks = pathMatchers(baseDir, plainTextMasks);
    }

    private Collection<PathMatcher> pathMatchers(Path basePath, Collection<String> pathExpressions) {
        return pathExpressions.stream()
                .map(o -> basePath.getFileSystem().getPathMatcher("glob:" + o))
                .collect(Collectors.toList());
    }

    public List<SourceFile> parse(Path searchDir, Collection<Path> alreadyParsed) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        if (!searchDir.toFile().exists()) {
            return sourceFiles;
        }
        Consumer<Throwable> errorConsumer = t -> logger.debug("Error parsing", t);
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(errorConsumer);

        try {
            sourceFiles.addAll(parseSourceFiles(searchDir, alreadyParsed, ctx));
            List<PlainText> parseFailures = ParsingExecutionContextView.view(ctx).pollParseFailures();
            if (!parseFailures.isEmpty()) {
                logger.warn("There were problems parsing " + parseFailures.size() + " sources:");
                for (PlainText parseFailure : parseFailures) {
                    logger.warn("  " + parseFailure.getSourcePath());
                }
                logger.warn("Execution will continue but these files are unlikely to be affected by refactoring recipes");
                sourceFiles.addAll(parseFailures);
            }
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }

        return sourceFiles;
    }

    @SuppressWarnings({"DuplicatedCode", "unchecked"})
    public <S extends SourceFile> List<S> parseSourceFiles(
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) throws IOException {

        List<Path> resources = new ArrayList<>();
        List<Path> quarkPaths = new ArrayList<>();
        List<Path> plainTextPaths = new ArrayList<>();
        Files.walkFileTree(searchDir, Collections.emptySet(), 16, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isExcluded(dir) || isIgnoredDirectory(searchDir, dir) || excludedDirectories.contains(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isOther() && !attrs.isSymbolicLink() &&
                    !alreadyParsed.contains(file) && !isExcluded(file)) {
                    if (isOverSizeThreshold(attrs.size())) {
                        logger.info("Parsing as quark " + file + " as its size " + attrs.size() / (1024L * 1024L) +
                                    "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
                        quarkPaths.add(file);
                    } else if (isParsedAsPlainText(file)) {
                        plainTextPaths.add(file);
                    } else {
                        resources.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Stream<S> sourceFiles = Stream.empty();

        List<Path> javaPaths = new ArrayList<>();

        JsonParser jsonParser = new JsonParser();
        List<Path> jsonPaths = new ArrayList<>();

        XmlParser xmlParser = new XmlParser();
        List<Path> xmlPaths = new ArrayList<>();

        YamlParser yamlParser = new YamlParser();
        List<Path> yamlPaths = new ArrayList<>();

        PropertiesParser propertiesParser = new PropertiesParser();
        List<Path> propertiesPaths = new ArrayList<>();

        ProtoParser protoParser = new ProtoParser();
        List<Path> protoPaths = new ArrayList<>();

        PythonParser pythonParser = PythonParser.builder().build();
        List<Path> pythonPaths = new ArrayList<>();

        HclParser hclParser = HclParser.builder().build();
        List<Path> hclPaths = new ArrayList<>();

        PlainTextParser plainTextParser = new PlainTextParser();

        QuarkParser quarkParser = new QuarkParser();

        resources.forEach(path -> {
            // See https://github.com/quarkusio/quarkus/blob/main/devtools/project-core-extension-codestarts/src/main/resources/codestarts/quarkus/extension-codestarts/resteasy-reactive-codestart/java/src/main/java/org/acme/%7Bresource.class-name%7D.tpl.qute.java
            // for an example of why we don't want qute files be parsed as java
            if (javaParser.accept(path) && !path.endsWith(".qute.java")) {
                javaPaths.add(path);
            } else if (jsonParser.accept(path)) {
                jsonPaths.add(path);
            } else if (xmlParser.accept(path)) {
                xmlPaths.add(path);
            } else if (yamlParser.accept(path)) {
                yamlPaths.add(path);
            } else if (propertiesParser.accept(path)) {
                propertiesPaths.add(path);
            } else if (protoParser.accept(path)) {
                protoPaths.add(path);
            } else if(pythonParser.accept(path)) {
                pythonPaths.add(path);
            } else if (hclParser.accept(path)) {
                hclPaths.add(path);
            } else if (quarkParser.accept(path)) {
                quarkPaths.add(path);
            }
        });

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) javaParser.parse(javaPaths, baseDir, ctx));
        alreadyParsed.addAll(javaPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) jsonParser.parse(jsonPaths, baseDir, ctx));
        alreadyParsed.addAll(jsonPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) xmlParser.parse(xmlPaths, baseDir, ctx));
        alreadyParsed.addAll(xmlPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) yamlParser.parse(yamlPaths, baseDir, ctx));
        alreadyParsed.addAll(yamlPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) propertiesParser.parse(propertiesPaths, baseDir, ctx));
        alreadyParsed.addAll(propertiesPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) protoParser.parse(protoPaths, baseDir, ctx));
        alreadyParsed.addAll(protoPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) pythonParser.parse(pythonPaths, baseDir, ctx));
        alreadyParsed.addAll(pythonPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) hclParser.parse(hclPaths, baseDir, ctx));
        alreadyParsed.addAll(hclPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) plainTextParser.parse(plainTextPaths, baseDir, ctx));
        alreadyParsed.addAll(plainTextPaths);

        sourceFiles = Stream.concat(sourceFiles, (Stream<S>) quarkParser.parse(quarkPaths, baseDir, ctx));
        alreadyParsed.addAll(quarkPaths);

        return sourceFiles.collect(Collectors.toList());
    }

    private boolean isOverSizeThreshold(long fileSize) {
        return sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L;
    }

    private boolean isExcluded(Path path) {
        if (!exclusions.isEmpty()) {
            for (PathMatcher excluded : exclusions) {
                if (excluded.matches(baseDir.relativize(path))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isParsedAsPlainText(Path path) {
        if (!plainTextMasks.isEmpty()) {
            Path computed = baseDir.relativize(path);
            if (!computed.startsWith("/")) {
                computed = Paths.get("/").resolve(computed);
            }
            for (PathMatcher matcher : plainTextMasks) {
                if (matcher.matches(computed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isIgnoredDirectory(Path searchDir, Path path) {
        for (Path pathSegment : searchDir.relativize(path)) {
            if (DEFAULT_IGNORED_DIRECTORIES.contains(pathSegment.toString())) {
                return true;
            }
        }
        return false;
    }
}
