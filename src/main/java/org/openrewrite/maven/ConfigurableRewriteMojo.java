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

import static java.util.Collections.emptyMap;

@SuppressWarnings("FieldMayBeFinal")
public abstract class ConfigurableRewriteMojo extends AbstractMojo {

    @Parameter(property = "rewrite.configLocation", alias = "configLocation", defaultValue = "${maven.multiModuleProjectDirectory}/rewrite.yml")
    protected String configLocation;

    @Parameter(property = "activeRecipes")
    protected List<String> activeRecipes = Collections.emptyList();

    @Nullable
    @Parameter(property = "rewrite.activeRecipes")
    protected String rewriteActiveRecipes;

    @Parameter(property = "activeStyles")
    protected Set<String> activeStyles = Collections.emptySet();

    @Nullable
    @Parameter(property = "rewrite.activeStyles")
    protected String rewriteActiveStyles;

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

    @Parameter(property = "exclusions")
    private Set<String> exclusions = Collections.emptySet();

    @Nullable
    @Parameter(property = "rewrite.exclusions")
    private String rewriteExclusions;

    protected Set<String> getExclusions() {
        if (rewriteExclusions == null) {
            return exclusions;
        } else {
            Set<String> allExclusions = toSet(rewriteExclusions);
            allExclusions.addAll(exclusions);
            return allExclusions;
        }
    }

    @Parameter(property = "plainTextMasks")
    private Set<String> plainTextMasks = new HashSet<>();

    @Nullable
    @Parameter(property = "rewrite.plainTextMasks")
    private String rewritePlainTextMasks;

    protected Set<String> getPlainTextMasks() {
        if (plainTextMasks.isEmpty() && rewritePlainTextMasks == null) {
            //If not defined, use a default set of masks
            return new HashSet<>(Arrays.asList(
                    "**/META-INF/services/**",
                    "**/META-INF/spring.factories",
                    "**/META-INF/spring/**",
                    "**/*.bash",
                    "**/*.bat",
                    "**/CODEOWNERS",
                    "**/*.config",
                    "**/Dockerfile",
                    "**/.gitattributes",
                    "**/.gitignore",
                    "**/.java-version",
                    "**/Jenkinsfile",
                    "**/*.jsp",
                    "**/*.ksh",
                    "**/lombok.config",
                    "**/*.md",
                    "**/*.qute.java",
                    "**/.sdkmanrc",
                    "**/*.sh",
                    "**/*.sql",
                    "**/*.txt"
            ));
        } else {
            Set<String> masks = toSet(rewritePlainTextMasks);
            masks.addAll(plainTextMasks);
            return masks;
        }
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
    private String recipeArtifactCoordinates;

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
                    Set<String> res = toLinkedHashSet(rewriteActiveRecipes);
                    if (res.isEmpty()) {
                        res.addAll(
                                activeRecipes
                                        .stream()
                                        .filter(Objects::nonNull)
                                        .collect(Collectors.toList())
                        );
                    }
                    computedRecipes = Collections.unmodifiableSet(res);
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
                    Set<String> res = toSet(rewriteActiveStyles);
                    if (res.isEmpty()) {
                        res.addAll(activeStyles);
                    }
                    computedStyles = Collections.unmodifiableSet(res);
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
                    computedRecipeArtifactCoordinates = Collections.unmodifiableSet(toSet(recipeArtifactCoordinates));
                }
            }
        }

        //noinspection ConstantConditions
        return computedRecipeArtifactCoordinates;
    }

    private static Set<String> toSet(@Nullable String propertyValue) {
        return Optional.ofNullable(propertyValue)
                .filter(s -> !s.isEmpty())
                .map(s -> new HashSet<>(Arrays.asList(s.split(","))))
                .orElseGet(HashSet::new);
    }

    private static Set<String> toLinkedHashSet(@Nullable String propertyValue) {
        return Optional.ofNullable(propertyValue)
                .filter(s -> !s.isEmpty())
                .map(s -> new LinkedHashSet<>(Arrays.asList(s.split(","))))
                .orElseGet(LinkedHashSet::new);
    }
}
