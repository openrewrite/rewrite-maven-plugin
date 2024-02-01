/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import com.puppycrawl.tools.checkstyle.Checker;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.openrewrite.config.Environment;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.style.CheckstyleConfigLoader;
import org.openrewrite.style.NamedStyles;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;

@SuppressWarnings("FieldMayBeFinal")
public abstract class ConfigurableRewriteMojo extends AbstractMojo {

    @Parameter(property = "rewrite.configLocation", alias = "configLocation", defaultValue = "${maven.multiModuleProjectDirectory}/rewrite.yml")
    protected String configLocation;

    @Nullable
    @Parameter(property = "rewrite.activeRecipes")
    protected LinkedHashSet<String> activeRecipes;
    /**
     * @deprecated Use {@code rewrite.activeRecipes} instead.
     */
    @Nullable
    @Parameter(property = "activeRecipes")
    @Deprecated
    protected LinkedHashSet<String> deprecatedActiveRecipes;

    @Nullable
    @Parameter(property = "rewrite.activeStyles")
    protected LinkedHashSet<String> activeStyles;
    /**
     * @deprecated Use {@code rewrite.activeStyles} instead.
     */
    @Nullable
    @Parameter(property = "activeStyles")
    @Deprecated
    protected LinkedHashSet<String> deprecatedActiveStyles;

    @Nullable
    @Parameter(property = "rewrite.metricsUri", alias = "metricsUri")
    protected String metricsUri;

    @Nullable
    @Parameter(property = "rewrite.metricsUsername", alias = "metricsUsername")
    protected String metricsUsername;

    @Nullable
    @Parameter(property = "rewrite.metricsPassword", alias = "metricsPassword")
    protected String metricsPassword;

    @Parameter(property = "rewrite.pomCacheEnabled", alias = "pomCacheEnabled", defaultValue = "true")
    protected boolean pomCacheEnabled;

    @Nullable
    @Parameter(property = "rewrite.pomCacheDirectory", alias = "pomCacheDirectory")
    protected String pomCacheDirectory;

    @Parameter(property = "rewrite.skip", defaultValue = "false")
    protected boolean rewriteSkip;

    /**
     * When enabled, skip parsing Maven `pom.xml`s, and any transitive poms, as source files.
     * This can be an efficiency improvement in certain situations.
     */
    @Parameter(property = "skipMavenParsing", defaultValue = "false")
    protected boolean skipMavenParsing;

    @Nullable
    @Parameter(property = "rewrite.checkstyleConfigFile", alias = "checkstyleConfigFile")
    protected String checkstyleConfigFile;

    @Nullable
    @Parameter(property = "rewrite.checkstyleDetectionEnabled", alias = "checkstyleDetectionEnabled", defaultValue = "true")
    protected boolean checkstyleDetectionEnabled;

    @Nullable
    @Parameter(property = "rewrite.exclusions")
    private LinkedHashSet<String> exclusions;
    /**
     * @deprecated Use {@code rewrite.exclusions} instead.
     */
    @Nullable
    @Deprecated
    @Parameter(property = "exclusions")
    private LinkedHashSet<String> deprecatedExclusions;

    protected Set<String> getExclusions() {
        return getMergedAndCleaned(exclusions, deprecatedExclusions);
    }

    @Nullable
    @Parameter(property = "rewrite.plainTextMasks")
    private LinkedHashSet<String> plainTextMasks;
    @Nullable
    @Parameter(property = "plainTextMasks")
    @Deprecated
    private LinkedHashSet<String> deprecatedPlainTextMasks;

    protected Set<String> getPlainTextMasks() {
        Set<String> masks = getMergedAndCleaned(plainTextMasks, deprecatedPlainTextMasks);
        if (!masks.isEmpty()) {
            return masks;
        }
        //If not defined, use a default set of masks
        return new HashSet<>(Arrays.asList(
                "**/*.adoc",
                "**/*.bash",
                "**/*.bat",
                "**/CODEOWNERS",
                "**/*.css",
                "**/*.config",
                "**/Dockerfile*",
                "**/.gitattributes",
                "**/.gitignore",
                "**/*.htm*",
                "**/gradlew",
                "**/.java-version",
                "**/*.jsp",
                "**/*.ksh",
                "**/lombok.config",
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
                "**/*.txt",
                "**/*.py"
        ));
    }

    @Nullable
    @Parameter(property = "sizeThresholdMb", defaultValue = "10")
    protected int sizeThresholdMb;

    /**
     * Whether to throw an exception if an activeRecipe fails configuration validation.
     * This may happen if the activeRecipe is improperly configured, or any downstream recipes are improperly configured.
     * <p>
     * For the time, this default is "false" to prevent one improperly recipe from failing the build.
     * In the future, this default may be changed to "true" to be more restrictive.
     */
    @Parameter(property = "rewrite.failOnInvalidActiveRecipes", alias = "failOnInvalidActiveRecipes", defaultValue = "false")
    protected boolean failOnInvalidActiveRecipes;

    @Parameter(property = "rewrite.runPerSubmodule", alias = "runPerSubmodule", defaultValue = "false")
    protected boolean runPerSubmodule;

    @Nullable
    @Parameter(property = "rewrite.recipeArtifactCoordinates")
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
                    computedRecipes = getMergedAndCleaned(activeRecipes, deprecatedActiveRecipes);
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
                    computedStyles = getMergedAndCleaned(activeStyles, deprecatedActiveStyles);
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
                styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(Paths.get(checkstyleConfigFile), emptyMap()));
            } else if (checkstyleDetectionEnabled && checkstylePlugin != null) {
                Object checkstyleConfRaw = checkstylePlugin.getConfiguration();
                if (checkstyleConfRaw instanceof Xpp3Dom) {
                    Xpp3Dom xmlCheckstyleConf = (Xpp3Dom) checkstyleConfRaw;
                    Xpp3Dom xmlConfigLocation = xmlCheckstyleConf.getChild("configLocation");

                    if (xmlConfigLocation == null) {
                        // When no config location is specified, the maven-checkstyle-plugin falls back on sun_checks.xml
                        try (InputStream is = Checker.class.getResourceAsStream("/sun_checks.xml")) {
                            if (is != null) {
                                styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(is, emptyMap()));
                            }
                        }
                    } else {
                        // resolve location against plugin location (could be in parent pom)
                        Path configPath = Paths.get(checkstylePlugin.getLocation("").getSource().getLocation())
                                .resolveSibling(xmlConfigLocation.getValue());
                        if (configPath.toFile().exists()) {
                            styles.add(CheckstyleConfigLoader.loadCheckstyleConfig(configPath, emptyMap()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLog().warn("Unable to parse checkstyle configuration. Checkstyle will not inform rewrite execution.", e);
        }
        return styles;
    }

    protected Set<String> getRecipeArtifactCoordinates() {
        if (computedRecipeArtifactCoordinates == null) {
            synchronized (this) {
                if (computedRecipeArtifactCoordinates == null) {
                    computedRecipeArtifactCoordinates = getMergedAndCleaned(recipeArtifactCoordinates, null);
                }
            }
        }
        //noinspection ConstantConditions
        return computedRecipeArtifactCoordinates;
    }

    private static Set<String> getMergedAndCleaned(@Nullable LinkedHashSet<String> set, @Nullable LinkedHashSet<String> deprecatedSet) {
        Stream<String> merged = Stream.empty();
        if (set != null) {
            merged = set.stream();
        }
        if (deprecatedSet != null) {
            merged = Stream.concat(merged, deprecatedSet.stream());
        }
        LinkedHashSet<String> collected = merged
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(collected);
    }
}
