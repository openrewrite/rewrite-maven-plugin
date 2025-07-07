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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jspecify.annotations.Nullable;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.StringUtils;
import org.openrewrite.style.NamedStyles;

import java.util.*;

/**
 * Generate a report of the available recipes and styles found on the classpath.<br>
 * <br>
 * Can also be used to display information about a specific recipe. For example:<br>
 * {@code ./mvnw rewrite:discover -Ddetail=true -Drecipe=org.openrewrite.java.format.AutoFormat}
 */
@Mojo(name = "discover", threadSafe = true, requiresProject = false, aggregator = true)
@SuppressWarnings("unused")
public class RewriteDiscoverMojo extends AbstractRewriteMojo {

    /**
     * The name of a specific recipe to show details for. For example:<br>
     * {@code ./mvnw rewrite:discover -Ddetail=true -Drecipe=org.openrewrite.java.format.AutoFormat}
     */
    @Parameter(property = "recipe")
    @Nullable
    String recipe;

    /**
     * Whether to display recipe details such as displayName, description, and configuration options.
     */
    @Parameter(property = "detail", defaultValue = "false")
    boolean detail;

    /**
     * The maximum level of recursion to display recipe descriptors under recipeList.
     */
    @Parameter(property = "recursion", defaultValue = "0")
    int recursion;


    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();
        Collection<RecipeDescriptor> availableRecipeDescriptors = env.listRecipeDescriptors();
        if (recipe != null) {
            RecipeDescriptor rd = getRecipeDescriptor(recipe, availableRecipeDescriptors);
            writeRecipeDescriptor(rd, detail, 0, 0);
        } else {
            Collection<RecipeDescriptor> activeRecipeDescriptors = new HashSet<>();
            for (String activeRecipe : getActiveRecipes()) {
                RecipeDescriptor rd = getRecipeDescriptor(activeRecipe, availableRecipeDescriptors);
                activeRecipeDescriptors.add(rd);
            }
            writeDiscovery(availableRecipeDescriptors, activeRecipeDescriptors, env.listStyles());
        }
    }

    private static final String RECIPE_NOT_FOUND_EXCEPTION_MSG = "Could not find recipe '%s' among available recipes";

    private static RecipeDescriptor getRecipeDescriptor(String recipe, Collection<RecipeDescriptor> recipeDescriptors) throws MojoExecutionException {
        return recipeDescriptors.stream()
                .filter(r -> r.getName().equalsIgnoreCase(recipe))
                .findAny()
                .orElseThrow(() -> new MojoExecutionException(String.format(RECIPE_NOT_FOUND_EXCEPTION_MSG, recipe)));
    }

    private void writeDiscovery(Collection<RecipeDescriptor> availableRecipeDescriptors, Collection<RecipeDescriptor> activeRecipeDescriptors, Collection<NamedStyles> availableStyles) {
        List<RecipeDescriptor> availableRecipesSorted = new ArrayList<>(availableRecipeDescriptors);
        availableRecipesSorted.sort(Comparator.comparing(RecipeDescriptor::getName, String.CASE_INSENSITIVE_ORDER));
        getLog().info("Available Recipes:");
        for (RecipeDescriptor recipeDescriptor : availableRecipesSorted) {
            writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
        }

        List<NamedStyles> availableStylesSorted = new ArrayList<>(availableStyles);
        availableStylesSorted.sort(Comparator.comparing(NamedStyles::getName, String.CASE_INSENSITIVE_ORDER));
        getLog().info("");
        getLog().info("Available Styles:");
        for (NamedStyles style : availableStylesSorted) {
            getLog().info("    " + style.getName());
        }

        List<String> activeStylesSorted = new ArrayList<>(getActiveStyles());
        activeStylesSorted.sort(String.CASE_INSENSITIVE_ORDER);
        getLog().info("");
        getLog().info("Active Styles:");
        for (String activeStyle : activeStylesSorted) {
            getLog().info("    " + activeStyle);
        }

        List<RecipeDescriptor> activeRecipesSorted = new ArrayList<>(activeRecipeDescriptors);
        activeRecipesSorted.sort(Comparator.comparing(RecipeDescriptor::getName, String.CASE_INSENSITIVE_ORDER));
        getLog().info("");
        getLog().info("Active Recipes:");
        for (RecipeDescriptor recipeDescriptor : activeRecipesSorted) {
            writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
        }

        getLog().info("");
        getLog().info("Found " + availableRecipeDescriptors.size() + " available recipes and " + availableStyles.size() + " available styles.");
        getLog().info("Configured with " + activeRecipeDescriptors.size() + " active recipes and " + getActiveStyles().size() + " active styles.");
    }

    private void writeRecipeDescriptor(RecipeDescriptor rd, boolean verbose, int currentRecursionLevel, int indentLevel) {
        String indent = StringUtils.repeat("    ", indentLevel);
        if (currentRecursionLevel <= recursion) {
            if (verbose) {

                getLog().info(indent + rd.getDisplayName());
                getLog().info(indent + "    " + rd.getName());
                // Recipe parsed from yaml might have null description, even though that isn't technically allowed
                //noinspection ConstantConditions
                if (rd.getDescription() != null && !rd.getDescription().isEmpty()) {
                    getLog().info(indent + "    " + rd.getDescription());
                }

                if (!rd.getOptions().isEmpty()) {
                    getLog().info(indent + "options: ");
                    for (OptionDescriptor od : rd.getOptions()) {
                        getLog().info(indent + "    " + od.getName() + ": " + od.getType() + (od.isRequired() ? "!" : ""));
                        if (od.getDescription() != null && !od.getDescription().isEmpty()) {
                            getLog().info(indent + "    " + "    " + od.getDescription());
                        }
                    }
                }
            } else {
                getLog().info(indent + rd.getName());
            }

            if (!rd.getRecipeList().isEmpty() && (currentRecursionLevel + 1 <= recursion)) {
                getLog().info(indent + "recipeList:");
                for (RecipeDescriptor r : rd.getRecipeList()) {
                    writeRecipeDescriptor(r, verbose, currentRecursionLevel + 1, indentLevel + 1);
                }
            }

            if (verbose) {
                getLog().info("");
            }
        }
    }
}
