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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResourceParser {
    private final Log logger;
    private final Collection<String> exclusions;
    private final int sizeThresholdMb;

    public ResourceParser(Log logger, Collection<String> exclusions, int thresholdMb) {
        this.logger = logger;
        this.exclusions = exclusions;
        sizeThresholdMb = thresholdMb;
    }

    public List<SourceFile> parse(Path baseDir, Path searchDir, Collection<Path> alreadyParsed) {
        List<SourceFile> sourceFiles = new ArrayList<>();
        if (!searchDir.toFile().exists()) {
            return sourceFiles;
        }
        Consumer<Throwable> errorConsumer = t -> logger.error("Error parsing", t);
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(errorConsumer);

        sourceFiles.addAll(parseSourceFiles(baseDir, searchDir, alreadyParsed, ctx));
        return sourceFiles;
    }

    @SuppressWarnings("unchecked")
    public <S extends SourceFile> List<S> parseSourceFiles(
            Path baseDir,
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) {

        try (Stream<Path> resources = Files.find(searchDir, 16, (path, attrs) -> {
            // Prevent java files from being parsed as quarks.
            if (path.toString().endsWith(".java")) {
                return false;
            }

            if (alreadyParsed.contains(path)) {
                return false;
            }

            if (attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther() || attrs.size() == 0) {
                return false;
            }

            for (Path pathSegment : searchDir.relativize(path)) {
                String pathStr = pathSegment.toString();
                if ("target".equals(pathStr) || "build".equals(pathStr) || "out".equals(pathStr) ||
                        ".gradle".equals(pathStr) || "node_modules".equals(pathStr) || ".metadata".equals(pathStr) ||
                        ".DS_Store".equals(pathStr) || ".git".equals(pathStr) || ".idea".equals(pathStr)) {
                    return false;
                }
            }

            for (String exclusion : exclusions) {
                PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + exclusion);
                if (matcher.matches(baseDir.relativize(path))) {
                    alreadyParsed.add(path);
                    return false;
                }
            }

            long fileSize = attrs.size();
            if ((sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L)) {
                logger.info("Parsing as Quark " + path + " as its size + " + fileSize / (1024L * 1024L) +
                        "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
                return true;
            }

            return true;
        })) {
            List<Path> resourceFiles = resources.collect(Collectors.toList());
            List<S> sourceFiles = new ArrayList<>(resourceFiles.size());

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
            List<Path> quarkPaths = new ArrayList<>();

            resourceFiles.forEach(path -> {
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
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
