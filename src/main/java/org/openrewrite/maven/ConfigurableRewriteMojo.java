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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.MXSerializer;
import org.intellij.lang.annotations.Language;
import org.jspecify.annotations.Nullable;
import org.openrewrite.config.Environment;
import org.openrewrite.style.NamedStyles;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toCollection;
import static org.openrewrite.java.style.CheckstyleConfigLoader.loadCheckstyleConfig;

@SuppressWarnings("FieldMayBeFinal")
public abstract class ConfigurableRewriteMojo extends AbstractMojo {

    private static final String CHECKSTYLE_DOCTYPE = "module PUBLIC " +
                                                     "\"-//Checkstyle//DTD Checkstyle Configuration 1.3//EN\" " +
                                                     "\"https://checkstyle.org/dtds/configuration_1_3.dtd\"";

    /**
     * The OpenRewrite YAML configuration file, as a path or an {@code http(s)} URL.
     * A relative path is resolved against the directory of each module, so prefixing it with
     * <code>${maven.multiModuleProjectDirectory}</code> has every module use the same file at the root of the repository.
     */
    @Parameter(property = "rewrite.configLocation", alias = "configLocation", defaultValue = "${maven.multiModuleProjectDirectory}/rewrite.yml")
    protected String configLocation;

    /**
     * The recipes to run, by the name they are given in their {@code specs.openrewrite.org/v1beta/recipe} resource.
     * No recipe runs unless it is listed here.
     */
    @Parameter(property = "rewrite.activeRecipes")
    @Nullable
    protected LinkedHashSet<String> activeRecipes;

    /**
     * The styles to apply, by the name they are given in their {@code specs.openrewrite.org/v1beta/style} resource.
     * No style is applied unless it is listed here.
     */
    @Parameter(property = "rewrite.activeStyles")
    @Nullable
    protected LinkedHashSet<String> activeStyles;

    /**
     * Whether to cache downloaded POMs and Maven metadata on disk, in {@code pomCacheDirectory}.
     * When disabled, an in-memory cache is used instead, which makes runs slower on projects with deep dependency trees.
     */
    @Parameter(property = "rewrite.pomCacheEnabled", alias = "pomCacheEnabled", defaultValue = "true")
    protected boolean pomCacheEnabled;

    /**
     * The directory to create the {@code .rewrite-cache} directory with cached POMs and Maven metadata in.
     * Defaults to the user's home directory. Deleting the cache is safe, as it is populated again on the next run.
     */
    @Parameter(property = "rewrite.pomCacheDirectory", alias = "pomCacheDirectory")
    @Nullable
    protected String pomCacheDirectory;

    /**
     * Whether to skip the execution of the plugin, to disable it without removing its configuration.
     */
    @Parameter(property = "rewrite.skip", defaultValue = "false")
    protected boolean rewriteSkip;

    /**
     * Whether to skip parsing {@code pom.xml} files, and the POMs they inherit from, as source files.
     * This can make runs faster when no recipe changes POMs.
     */
    @Parameter(property = "skipMavenParsing", defaultValue = "false")
    protected boolean skipMavenParsing;

    /**
     * A Checkstyle configuration file to derive the coding style from, instead of detecting it from the Checkstyle plugin.
     */
    @Parameter(property = "rewrite.checkstyleConfigFile", alias = "checkstyleConfigFile")
    @Nullable
    protected String checkstyleConfigFile;

    /**
     * Whether to derive the coding style from the configuration of the Checkstyle plugin, when the build uses it.
     */
    @Parameter(property = "rewrite.checkstyleDetectionEnabled", alias = "checkstyleDetectionEnabled", defaultValue = "true")
    protected boolean checkstyleDetectionEnabled;

    /**
     * Paths not to parse, as <a href="https://docs.oracle.com/javase/8/docs/api/java/nio/file/PathMatcher.html">{@code PathMatcher}</a>
     * glob patterns relative to the root of the repository, where {@code **} matches any number of directories and
     * {@code *} a single directory or file name. For example, {@code docs&#47;**}.
     */
    @Parameter(property = "rewrite.exclusions")
    @Nullable
    private LinkedHashSet<String> exclusions;

    protected Set<String> getExclusions() {
        return getCleanedSet(exclusions);
    }

    /**
     * The files to parse as plain text, as glob patterns like those of {@code exclusions}, replacing the default masks.
     * The defaults cover common text files without a dedicated parser, such as Markdown, shell scripts and Dockerfiles.
     * Exclusions take precedence over plain text masks, and {@code additionalPlainTextMasks} has no effect when this is set.
     */
    @Parameter(property = "rewrite.plainTextMasks")
    @Nullable
    private LinkedHashSet<String> plainTextMasks;

    /**
     * Files to parse as plain text in addition to the default masks, as glob patterns like those of {@code exclusions}.
     * For example, {@code **&#47;*.ext}.
     */
    @Parameter(property = "rewrite.additionalPlainTextMasks")
    @Nullable
    private LinkedHashSet<String> additionalPlainTextMasks;

    protected Set<String> getPlainTextMasks() {
        Set<String> masks = getCleanedSet(plainTextMasks);
        if (!masks.isEmpty()) {
            return masks;
        }
        //If not defined, use a default set of masks
        masks = new HashSet<>(Arrays.asList(
                "**/*.adoc",
                "**/*.aj",
                "**/*.bash",
                "**/*.bat",
                "**/CODEOWNERS",
                "**/*.css",
                "**/*.config",
                "**/[dD]ockerfile*",
                "**/*.[dD]ockerfile",
                "**/*[cC]ontainerfile*",
                "**/*.[cC]ontainerfile",
                "**/*.env",
                "**/.gitattributes",
                "**/.gitignore",
                "**/*.htm*",
                "**/gradlew",
                "**/.java-version",
                "**/*.jelly",
                "**/*.jsp",
                "**/*.ksh",
                "**/*.lock",
                "**/lombok.config",
                "**/[mM]akefile",
                "**/*.md",
                "**/*.mf",
                "**/META-INF/services/**",
                "**/META-INF/spring/**",
                "**/META-INF/spring.factories",
                "**/mvnw",
                "**/mvnw.cmd",
                "**/*.qute.java",
                "**/.sdkmanrc",
                "**/*.sh",
                "**/*.sql",
                "**/*.svg",
                "**/*.tsx",
                "**/*.txt",
                "**/*.py"
        ));
        masks.addAll(getCleanedSet(additionalPlainTextMasks));
        return unmodifiableSet(masks);
    }

    /**
     * The size in megabytes above which files other than Java sources are not parsed.
     */
    @Parameter(property = "sizeThresholdMb", defaultValue = "10")
    protected int sizeThresholdMb;

    /**
     * Whether to fail the build when an active recipe, or a recipe it includes, is not configured correctly.
     * This may default to {@code true} in the future.
     */
    @Parameter(property = "rewrite.failOnInvalidActiveRecipes", alias = "failOnInvalidActiveRecipes", defaultValue = "false")
    protected boolean failOnInvalidActiveRecipes;

    /**
     * Whether to run recipes on each module separately, rather than once on all the modules of the build.
     */
    @Parameter(property = "rewrite.runPerSubmodule", alias = "runPerSubmodule", defaultValue = "false")
    protected boolean runPerSubmodule;

    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession mavenSession;

    @Parameter(defaultValue = "${plugin}", required = true, readonly = true)
    protected PluginDescriptor pluginDescriptor;

    protected enum State {
        SKIPPED,
        PROCESSED,
        TO_BE_PROCESSED
    }

    private static final String OPENREWRITE_PROCESSED_MARKER = "openrewrite.processed";

    protected void putState(State state) {
        //noinspection unchecked
        getPluginContext().put(OPENREWRITE_PROCESSED_MARKER, state.name());
    }

    private boolean hasState(MavenProject project) {
        Map<String, Object> pluginContext = mavenSession.getPluginContext(pluginDescriptor, project);
        return pluginContext.containsKey(OPENREWRITE_PROCESSED_MARKER);
    }

    protected boolean allProjectsMarked() {
        return mavenSession.getProjects().stream().allMatch(this::hasState);
    }

    /**
     * Recipe artifacts to add, as {@code groupId:artifactId:version} coordinates, instead of or in addition to plugin dependencies.
     * These are resolved from the repositories of the project rather than its plugin repositories.
     */
    @Parameter(property = "rewrite.recipeArtifactCoordinates")
    @Nullable
    private LinkedHashSet<String> recipeArtifactCoordinates;

    @Nullable
    private volatile Set<String> computedRecipes;

    @Nullable
    private volatile Set<String> computedStyles;

    @Nullable
    private volatile Set<String> computedRecipeArtifactCoordinates;

    protected Set<String> getActiveRecipes() {
        if (computedRecipes == null) {
            synchronized (this) {
                if (computedRecipes == null) {
                    computedRecipes = getCleanedSet(activeRecipes);
                }
            }
        }
        //noinspection ConstantConditions
        return computedRecipes;
    }

    protected Set<String> getActiveStyles() {
        if (computedStyles == null) {
            synchronized (this) {
                if (computedStyles == null) {
                    computedStyles = getCleanedSet(activeStyles);
                }
            }
        }
        //noinspection ConstantConditions
        return computedStyles;
    }

    protected List<NamedStyles> loadStyles(MavenProject project, Environment env) {
        List<NamedStyles> styles = env.activateStyles(getActiveStyles());
        try {
            Plugin checkstylePlugin = project.getPlugin("org.apache.maven.plugins:maven-checkstyle-plugin");
            if (checkstyleConfigFile != null && !checkstyleConfigFile.isEmpty()) {
                styles.add(loadCheckstyleConfig(Paths.get(checkstyleConfigFile), emptyMap()));
            } else if (checkstyleDetectionEnabled && checkstylePlugin != null) {
                Object checkstyleConfRaw = checkstylePlugin.getConfiguration();
                if (checkstyleConfRaw instanceof Xpp3Dom) {
                    Xpp3Dom xmlCheckstyleConf = (Xpp3Dom) checkstyleConfRaw;
                    Xpp3Dom xmlConfigLocation = xmlCheckstyleConf.getChild("configLocation");
                    Xpp3Dom xmlCheckstyleRules = xmlCheckstyleConf.getChild("checkstyleRules");
                    if (xmlConfigLocation != null) {
                        // resolve location against plugin location (could be in parent pom)
                        Path configPath = Paths.get(checkstylePlugin.getLocation("").getSource().getLocation())
                                .resolveSibling(xmlConfigLocation.getValue());
                        if (configPath.toFile().exists()) {
                            styles.add(loadCheckstyleConfig(configPath, emptyMap()));
                        }
                    } else if (xmlCheckstyleRules != null && xmlCheckstyleRules.getChildCount() > 0) {
                        styles.add(loadCheckstyleConfig(toCheckStyleDocument(xmlCheckstyleRules.getChild(0)), emptyMap()));
                    } else {
                        // When no config location is specified, the maven-checkstyle-plugin falls back on sun_checks.xml
                        try (InputStream is = org.openrewrite.tools.checkstyle.Checker.class.getResourceAsStream("/sun_checks.xml")) {
                            if (is != null) {
                                styles.add(loadCheckstyleConfig(is, emptyMap()));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn("Unable to parse checkstyle configuration. Checkstyle will not inform rewrite execution.", e);
        }
        return styles;
    }

    private @Language("XML") String toCheckStyleDocument(final Xpp3Dom dom) throws IOException {
        StringWriter stringWriter = new StringWriter();

        MXSerializer serializer = new MXSerializer();
        serializer.setOutput(stringWriter);
        serializer.docdecl(CHECKSTYLE_DOCTYPE);

        dom.writeToSerializer("", serializer);

        return stringWriter.toString();
    }

    protected Set<String> getRecipeArtifactCoordinates() {
        if (computedRecipeArtifactCoordinates == null) {
            synchronized (this) {
                if (computedRecipeArtifactCoordinates == null) {
                    computedRecipeArtifactCoordinates = getCleanedSet(recipeArtifactCoordinates);
                }
            }
        }
        //noinspection ConstantConditions
        return computedRecipeArtifactCoordinates;
    }

    private static Set<String> getCleanedSet(@Nullable Set<@Nullable String> set) {
        if (set == null) {
            return emptySet();
        }
        Set<String> cleaned = set.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(toCollection(LinkedHashSet::new));
        return unmodifiableSet(cleaned);
    }
}
