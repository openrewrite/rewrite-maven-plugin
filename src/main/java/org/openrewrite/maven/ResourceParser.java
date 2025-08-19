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

import org.apache.maven.plugin.logging.Log;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.hcl.HclParser;
import org.openrewrite.java.JavaParser;
import org.openrewrite.jgit.lib.FileMode;
import org.openrewrite.jgit.lib.Repository;
import org.openrewrite.jgit.treewalk.FileTreeIterator;
import org.openrewrite.jgit.treewalk.TreeWalk;
import org.openrewrite.jgit.treewalk.WorkingTreeIterator;
import org.openrewrite.jgit.treewalk.filter.PathFilterGroup;
import org.openrewrite.json.JsonParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.protobuf.ProtoParser;
import org.openrewrite.quark.QuarkParser;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.toml.TomlParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.yaml.YamlParser;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toList;

public class ResourceParser {
    private static final Set<String> DEFAULT_ACCEPTED_DIRECTORIES = new HashSet<>(singleton("src"));
    private static final Set<String> DEFAULT_IGNORED_DIRECTORIES = new HashSet<>(Arrays.asList(
            "build", "target", "out",
            ".sonar", ".gradle", ".idea", ".project", "node_modules", ".git", ".metadata", ".DS_Store", ".terraform"));

    private final Path baseDir;
    private final @Nullable Repository repository;
    private final Log logger;
    private final Collection<PathMatcher> exclusions;
    private final int sizeThresholdMb;
    private final Collection<Path> excludedDirectories;
    private final Collection<PathMatcher> plainTextMasks;

    /**
     * Sometimes java files will exist in the src/main/resources directory. For example, Drools:
     */
    private final JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder;

    private final KotlinParser.Builder kotlinParserBuilder;

    private final ExecutionContext ctx;

    public ResourceParser(Path baseDir, @Nullable Repository repository, Log logger, Collection<String> exclusions, Collection<String> plainTextMasks, int sizeThresholdMb, Collection<Path> excludedDirectories,
                          JavaParser.Builder<? extends JavaParser, ?> javaParserBuilder, KotlinParser.Builder kotlinParserBuilder, ExecutionContext ctx) {
        this.baseDir = baseDir;
        this.repository = repository;
        this.logger = logger;
        this.javaParserBuilder = javaParserBuilder;
        this.kotlinParserBuilder = kotlinParserBuilder;
        this.exclusions = pathMatchers(baseDir, exclusions);
        this.sizeThresholdMb = sizeThresholdMb;
        this.excludedDirectories = excludedDirectories;
        this.plainTextMasks = pathMatchers(baseDir, plainTextMasks);
        this.ctx = ctx;
    }

    private Collection<PathMatcher> pathMatchers(Path basePath, Collection<String> pathExpressions) {
        return pathExpressions.stream()
                .map(o -> basePath.getFileSystem().getPathMatcher("glob:" + o))
                .collect(toList());
    }

    public Stream<SourceFile> parse(Path searchDir, Collection<Path> alreadyParsed) {
        Stream<SourceFile> sourceFiles = Stream.empty();
        if (!searchDir.toFile().exists()) {
            return sourceFiles;
        }

        try {
            sourceFiles = Stream.concat(sourceFiles, parseSourceFiles(searchDir, alreadyParsed, ctx));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new UncheckedIOException(e);
        }

        return sourceFiles;
    }

    @SuppressWarnings({"DuplicatedCode", "unchecked"})
    public <S extends SourceFile> Stream<S> parseSourceFiles(
            Path searchDir,
            Collection<Path> alreadyParsed,
            ExecutionContext ctx) throws IOException {

        List<Path> resources = new ArrayList<>();
        List<Path> quarkPaths = new ArrayList<>();
        List<Path> plainTextPaths = new ArrayList<>();
        Files.walkFileTree(searchDir, emptySet(), 16, new SimpleFileVisitor<Path>() {
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
                        logger.info("Not parsing quark " + file + " as its size " + attrs.size() / (1024L * 1024L) +
                                    " MB exceeds size threshold " + sizeThresholdMb + " MB");
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

        JavaParser javaParser = javaParserBuilder.build();
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

        TomlParser tomlParser = new TomlParser();
        List<Path> tomlPaths = new ArrayList<>();

        KotlinParser kotlinParser = kotlinParserBuilder.build();
        List<Path> kotlinPaths = new ArrayList<>();

        GroovyParser groovyParser = GroovyParser.builder().build();
        List<Path> groovyPaths = new ArrayList<>();

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
            } else if (tomlParser.accept(path)) {
                tomlPaths.add(path);
            } else if (kotlinParser.accept(path)) {
                kotlinPaths.add(path);
            } else if (groovyParser.accept(path)) {
                groovyPaths.add(path);
            } else if (hclParser.accept(path)) {
                hclPaths.add(path);
            } else if (quarkParser.accept(path)) {
                quarkPaths.add(path);
            }
        });

        if (!javaPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) javaParser.parse(javaPaths, baseDir, ctx));
            alreadyParsed.addAll(javaPaths);
        }

        if (!jsonPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) jsonParser.parse(jsonPaths, baseDir, ctx));
            alreadyParsed.addAll(jsonPaths);
        }

        if (!xmlPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) xmlParser.parse(xmlPaths, baseDir, ctx));
            alreadyParsed.addAll(xmlPaths);
        }

        if (!yamlPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) yamlParser.parse(yamlPaths, baseDir, ctx));
            alreadyParsed.addAll(yamlPaths);
        }

        if (!propertiesPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) propertiesParser.parse(propertiesPaths, baseDir, ctx));
            alreadyParsed.addAll(propertiesPaths);
        }

        if (!protoPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) protoParser.parse(protoPaths, baseDir, ctx));
            alreadyParsed.addAll(protoPaths);
        }

        if (!tomlPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) tomlParser.parse(tomlPaths, baseDir, ctx));
            alreadyParsed.addAll(tomlPaths);
        }

        if (!kotlinPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) kotlinParser.parse(kotlinPaths, baseDir, ctx));
            alreadyParsed.addAll(kotlinPaths);
        }

        if (!groovyPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) groovyParser.parse(groovyPaths, baseDir, ctx));
            alreadyParsed.addAll(groovyPaths);
        }

        if (!hclPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) hclParser.parse(hclPaths, baseDir, ctx));
            alreadyParsed.addAll(hclPaths);
        }

        if (!plainTextPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) plainTextParser.parse(plainTextPaths, baseDir, ctx));
            alreadyParsed.addAll(plainTextPaths);
        }

        if (!quarkPaths.isEmpty()) {
            sourceFiles = Stream.concat(sourceFiles, (Stream<S>) quarkParser.parse(quarkPaths, baseDir, ctx));
            alreadyParsed.addAll(quarkPaths);
        }

        return sourceFiles;
    }

    private boolean isOverSizeThreshold(long fileSize) {
        return sizeThresholdMb > 0 && fileSize > sizeThresholdMb * 1024L * 1024L;
    }

    private boolean isExcluded(Path path) {
        for (PathMatcher excluded : exclusions) {
            if (excluded.matches(path)) {
                return true;
            }
        }
        // PathMather will not evaluate the path "pom.xml" to be matched by the pattern "**/pom.xml"
        // This is counter-intuitive for most users and would otherwise require separate exclusions for files at the root and files in subdirectories
        if(!path.isAbsolute() && !path.startsWith(File.separator)) {
            return isExcluded(Paths.get("/" + path));
        }

        if (repository != null) {
            String repoRelativePath = path.toString();
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
                        } else {
                            walk.enterSubtree();
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
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
            if (DEFAULT_ACCEPTED_DIRECTORIES.contains(pathSegment.toString())){
                return false;
            }
            if (DEFAULT_IGNORED_DIRECTORIES.contains(pathSegment.toString())) {
                return true;
            }
        }
        return false;
    }
}
