package org.openrewrite.maven;

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
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

        sourceFiles.addAll(parseSourceFiles(baseDir, new JsonParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new XmlParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new YamlParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new PropertiesParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new ProtoParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, HclParser.builder().build(), searchDir, alreadyParsed, ctx));

        sourceFiles.addAll(parseQuarks(baseDir, searchDir, alreadyParsed, ctx));
        return sourceFiles;
    }

    public <S extends SourceFile> List<S> parseSourceFiles(
            Path baseDir,
            Parser<S> parser,
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) {
        try (Stream<Path> resources = Files.find(searchDir, 16, (path, attrs) -> {
            if (!parser.accept(path)) {
                return false;
            }

            for (Path pathSegment : searchDir.relativize(path)) {
                String pathStr = pathSegment.toString();
                if ("target".equals(pathStr) || "build".equals(pathStr) || "out".equals(pathStr) ||
                        ".gradle".equals(pathStr) || "node_modules".equals(pathStr) || ".metadata".equals(pathStr)) {
                    return false;
                }
            }

            if (attrs.isDirectory() || attrs.size() == 0) {
                return false;
            }

            if (alreadyParsed.contains(path)) {
                return false;
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
                return false;
            }

            return true;
        })) {
            List<Path> resourceFiles = resources.collect(Collectors.toList());
            alreadyParsed.addAll(resourceFiles);
            return parser.parse(resourceFiles, baseDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }

    public <S extends SourceFile> List<S> parseQuarks(
            Path baseDir,
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) {
        QuarkParser parser = new QuarkParser();
        try (Stream<Path> resources = Files.find(searchDir, 16, (path, attrs) -> {
            if (path.toString().endsWith(".java")) {
                return false;
            }

            if (!parser.accept(path)) {
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

            if (attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther() || attrs.size() == 0) {
                return false;
            }

            if (alreadyParsed.contains(path)) {
                return false;
            }

            for (String exclusion : exclusions) {
                PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + exclusion);
                if (matcher.matches(baseDir.relativize(path))) {
                    alreadyParsed.add(path);
                    return false;
                }
            }

            return true;
        })) {
            List<Path> resourceFiles = resources.collect(Collectors.toList());
            alreadyParsed.addAll(resourceFiles);
            //noinspection unchecked
            return (List<S>) parser.parse(resourceFiles, baseDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
