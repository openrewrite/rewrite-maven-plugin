package org.openrewrite.maven;

import org.apache.maven.plugin.logging.Log;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ResourceParser {
    private final Log logger;

    public ResourceParser(Log logger) {
        this.logger = logger;
    }

    public List<SourceFile> parse(Path projectDir, Collection<Path> alreadyParsed) {
        Consumer<Throwable> errorConsumer = t -> logger.error("Error parsing", t);
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(errorConsumer);

        List<SourceFile> sourceFiles = new ArrayList<>();
        sourceFiles.addAll(parseSourceFiles(ctx, new JsonParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, new XmlParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, new YamlParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, new PropertiesParser(), projectDir, alreadyParsed));
        sourceFiles.addAll(parseSourceFiles(ctx, HclParser.builder().build(), projectDir, alreadyParsed));

        return sourceFiles;
    }

    public <S extends SourceFile> List<S> parseSourceFiles(InMemoryExecutionContext ctx,
                                                           Parser<S> parser,
                                                           Path projectDir,
                                                           Collection<Path> alreadyParsed) {
        try {
            List<Path> resourceFiles = Files.find(projectDir, 16, (path, attrs) -> {
                try {
                    if (path.toString().contains("/target/")) {
                        return false;
                    }

                    if (alreadyParsed.contains(projectDir.relativize(path))) {
                        return false;
                    }

                    if (path.toString().contains("/build/") || path.toString().contains("/out/")) {
                        return false;
                    }

                    if (attrs.isDirectory() || Files.size(path) == 0) {
                        return false;
                    }
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
                return parser.accept(path);
            }).collect(Collectors.toList());

            return parser.parse(resourceFiles, projectDir, ctx);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }
    }
}
