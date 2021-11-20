package org.openrewrite.maven;

import org.apache.maven.plugin.logging.Log;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.json.JsonParser;
import org.openrewrite.properties.PropertiesParser;
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
        if(!searchDir.toFile().exists()) {
            return sourceFiles;
        }
        Consumer<Throwable> errorConsumer = t -> logger.error("Error parsing", t);
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(errorConsumer);

        sourceFiles.addAll(parseSourceFiles(baseDir, new JsonParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new XmlParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new YamlParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, new PropertiesParser(), searchDir, alreadyParsed, ctx));
        sourceFiles.addAll(parseSourceFiles(baseDir, HclParser.builder().build(), searchDir, alreadyParsed, ctx));

        return sourceFiles;
    }

    public <S extends SourceFile> List<S> parseSourceFiles(Path baseDir,
                                                           Parser<S> parser,
                                                           Path searchDir,
                                                           Collection<Path> alreadyParsed,
                                                           ExecutionContext ctx) {
        try {
            List<Path> resourceFiles = Files.find(searchDir, 16, (path, attrs) -> {
                try {
                    if (path.toString().contains("/target/") || path.toString().contains("/build/")
                            || path.toString().contains("/out/") || path.toString().contains("/node_modules/") || path.toString().contains("/.metadata/")) {
                        return false;
                    }
                    for (String exclusion : exclusions) {
                        PathMatcher matcher = baseDir.getFileSystem().getPathMatcher("glob:" + exclusion);
                        if(matcher.matches(baseDir.relativize(path))) {
                            return false;
                        }
                    }

                    if (alreadyParsed.contains(searchDir.relativize(path))) {
                        return false;
                    }

                    if (attrs.isDirectory() || Files.size(path) == 0) {
                        return false;
                    }
                    long fileSize = Files.size(path);
                    if(sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L) {
                        alreadyParsed.add(path);
                        logger.info("Skipping parsing " + path + " as its size + "  + fileSize / (1024L * 1024L) +
                                "Mb exceeds size threshold " + sizeThresholdMb + "Mb");
                        return false;
                    }

                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
                return parser.accept(path);
            }).collect(Collectors.toList());

            return parser.parse(resourceFiles, baseDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
