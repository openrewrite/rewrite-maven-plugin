package org.openrewrite.maven;

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.protobuf.ProtoParser;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ResourceParser {
    private final Path baseDir;
    private final Log logger;
    private final Collection<PathMatcher> exclusions;
    private final int sizeThresholdMb;
    private final Collection<Path> skipOtherMavenProjects;

    public ResourceParser(Path baseDir, Log logger, Collection<String> exclusions, int sizeThresholdMb, Collection<Path> skipOtherMavenProjects) {
        this.baseDir = baseDir;
        this.logger = logger;
        this.exclusions = mergeExclusions(baseDir, exclusions);
        this.sizeThresholdMb = sizeThresholdMb;
        this.skipOtherMavenProjects = skipOtherMavenProjects;
    }

    private static List<PathMatcher> mergeExclusions(Path baseDir, Collection<String> exclusions) {
        Set<String> mergedExclusions = new HashSet<>(new ArrayList<>(Arrays.asList("build", "target", "out", ".gradle", ".idea", ".project", "node_modules", ".git", ".metadata", ".DS_Store")));
        mergedExclusions.addAll(exclusions);
        return mergedExclusions.stream().map(o -> baseDir.getFileSystem().getPathMatcher("glob:**/" + o)).collect(Collectors.toList());
    }

    public List<SourceFile> parse(Path searchDir, Collection<Path> alreadyParsed) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        if (!searchDir.toFile().exists()) {
            return sourceFiles;
        }
        Consumer<Throwable> errorConsumer = t -> logger.error("Error parsing", t);
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(errorConsumer);

        try {
            sourceFiles.addAll(parseSourceFiles(searchDir, alreadyParsed, ctx));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
        return sourceFiles;
    }

    @SuppressWarnings("unchecked")
    public <S extends SourceFile> List<S> parseSourceFiles(
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) throws IOException {

        List<Path> resources = new ArrayList<>();
        List<Path> quarkPaths = new ArrayList<>();

        Files.walkFileTree(searchDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(dir) || skipOtherMavenProjects.contains(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isOther() && !alreadyParsed.contains(file) && !isIgnored(file)) {
                    if (isOverSizeThreshold(attrs.size())) {
                        logger.info("Parsing as Quark " + file + " as its size + " + attrs.size() / (1024L * 1024L) +
                                "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
                        quarkPaths.add(file);
                    } else {
                        resources.add(file);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<S> sourceFiles = new ArrayList<>(resources.size());

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

        HclParser hclParser = HclParser.builder().build();
        List<Path> hclPaths = new ArrayList<>();

        QuarkParser quarkParser = new QuarkParser();

        resources.forEach(path -> {
            if (jsonParser.accept(path)) {
                jsonPaths.add(path);
            } else if (xmlParser.accept(path)) {
                xmlPaths.add(path);
            } else if (yamlParser.accept(path)) {
                yamlPaths.add(path);
            } else if (propertiesParser.accept(path)) {
                propertiesPaths.add(path);
            } else if (protoParser.accept(path)) {
                protoPaths.add(path);
            } else if (hclParser.accept(path)) {
                hclPaths.add(path);
            } else if (quarkParser.accept(path)) {
                quarkPaths.add(path);
            }
        });

        sourceFiles.addAll((List<S>) jsonParser.parse(jsonPaths, baseDir, ctx));
        alreadyParsed.addAll(jsonPaths);

        sourceFiles.addAll((List<S>) xmlParser.parse(xmlPaths, baseDir, ctx));
        alreadyParsed.addAll(xmlPaths);

        sourceFiles.addAll((List<S>) yamlParser.parse(yamlPaths, baseDir, ctx));
        alreadyParsed.addAll(yamlPaths);

        sourceFiles.addAll((List<S>) propertiesParser.parse(propertiesPaths, baseDir, ctx));
        alreadyParsed.addAll(propertiesPaths);

        sourceFiles.addAll((List<S>) protoParser.parse(protoPaths, baseDir, ctx));
        alreadyParsed.addAll(protoPaths);

        sourceFiles.addAll((List<S>) hclParser.parse(hclPaths, baseDir, ctx));
        alreadyParsed.addAll(hclPaths);

        sourceFiles.addAll((List<S>) quarkParser.parse(quarkPaths, baseDir, ctx));
        alreadyParsed.addAll(quarkPaths);

        return sourceFiles;
    }

    private boolean isOverSizeThreshold(long fileSize) {
        return (sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L);
    }

    private boolean isIgnored(Path baseDir) {
        for (PathMatcher ignored : exclusions) {
            if (ignored.matches(baseDir)) {
                return true;
            }
        }
        return false;
    }
}
