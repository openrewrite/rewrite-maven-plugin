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
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableSet;
import static org.openrewrite.java.style.CheckstyleConfigLoader.loadCheckstyleConfig;

@SuppressWarnings("FieldMayBeFinal")
public abstract class ConfigurableRewriteMojo extends AbstractMojo {

    private static final String CHECKSTYLE_DOCTYPE = "module PUBLIC "
                                                     + "\"-//Checkstyle//DTD Checkstyle Configuration 1.3//EN\" "
                                                     + "\"https://checkstyle.org/dtds/configuration_1_3.dtd\"";

    @Parameter(property = "rewrite.configLocation", alias = "configLocation", defaultValue = "${maven.multiModuleProjectDirectory}/rewrite.yml")
    protected String configLocation;

    @Nullable
    @Parameter(property = "rewrite.activeRecipes")
    protected LinkedHashSet<String> activeRecipes;

    @Nullable
    @Parameter(property = "rewrite.activeStyles")
    protected LinkedHashSet<String> activeStyles;

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

    protected Set<String> getExclusions() {
        return getCleanedSet(exclusions);
    }

    /**
     * Override default plain text masks. If this is specified,
     * {@code rewrite.additionalPlainTextMasks} will have no effect.
     */
    @Nullable
    @Parameter(property = "rewrite.plainTextMasks")
    private LinkedHashSet<String> plainTextMasks;

    /**
     * Allows to add additional plain text masks without overriding
     * the defaults.
     */
    @Nullable
    @Parameter(property = "rewrite.additionalPlainTextMasks")
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
                "**/Dockerfile*",
                "**/*.env",
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
                "**/*.tsx",
                "**/*.txt",
                "**/*.py"
        ));
        masks.addAll(getCleanedSet(additionalPlainTextMasks));
        return unmodifiableSet(masks);
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
        getPluginContext().put(OPENREWRITE_PROCESSED_MARKER, state.name());
    }

    private boolean hasState(MavenProject project) {
        Map<String, Object> pluginContext = mavenSession.getPluginContext(pluginDescriptor, project);
        return pluginContext.containsKey(OPENREWRITE_PROCESSED_MARKER);
    }

    protected boolean allProjectsMarked() {
        return mavenSession.getProjects().stream().allMatch(this::hasState);
    }

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
                        try (InputStream is = Checker.class.getResourceAsStream("/sun_checks.xml")) {
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

    private static Set<String> getCleanedSet(@Nullable Set<String> set) {
        if (set == null) {
            return Collections.emptySet();
        }
        Set<String> cleaned = set.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return unmodifiableSet(cleaned);
    }
}
