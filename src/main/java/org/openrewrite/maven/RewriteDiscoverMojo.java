package org.openrewrite.maven;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.maven.ui.RecipeDescriptorTreePrompter;
import org.openrewrite.style.NamedStyles;

import java.util.Collection;
import java.util.HashSet;

/**
 * Generate a report of available recipes found on the classpath.<br>
 * {@code ./mvnw rewrite:discover -Ddetail=true -Drecipe=<recipe-name>} to display recipe configuration details. For example:<br>
 * {@code ./mvnw rewrite:discover -Ddetail=true -Drecipe=org.openrewrite.java.format.AutoFormat}
 */
@Mojo(name = "discover", threadSafe = true)
public class RewriteDiscoverMojo extends AbstractRewriteMojo {

    /**
     * The name of a specific recipe to show details for. For example:<br>
     * {@code ./mvnw rewrite:discover -Ddetail=true -Drecipe=org.openrewrite.java.format.AutoFormat}
     */
    @Nullable
    @Parameter(property = "recipe")
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

    /**
     * Whether to enter an interactive shell to explore available recipes. For example:<br>
     * {@code ./mvnw rewrite:discover -Dinteractive}
     */
    @Parameter(property = "interactive", defaultValue = "false")
    boolean interactive;

    @Component
    private Prompter prompter;

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();
        Collection<RecipeDescriptor> availableRecipeDescriptors = env.listRecipeDescriptors();
        if (recipe != null) {
            RecipeDescriptor rd = getRecipeDescriptor(recipe, availableRecipeDescriptors);
            writeRecipeDescriptor(rd, detail, 0, 0);
        } else if (interactive) {
            getLog().info("Entering interactive mode, Ctrl-C to exit...");
            RecipeDescriptorTreePrompter treePrompter = new RecipeDescriptorTreePrompter(prompter);
            RecipeDescriptor rd = treePrompter.execute(availableRecipeDescriptors);
            writeRecipeDescriptor(rd, true, 0, 0);
        } else {
            Collection<RecipeDescriptor> activeRecipeDescriptors = new HashSet<>();
            for (String activeRecipe : activeRecipes) {
                RecipeDescriptor rd = getRecipeDescriptor(activeRecipe, availableRecipeDescriptors);
                activeRecipeDescriptors.add(rd);
            }
            writeDiscovery(availableRecipeDescriptors, activeRecipeDescriptors, env.listStyles());
        }
    }

    private void writeDiscovery(Collection<RecipeDescriptor> availableRecipeDescriptors, Collection<RecipeDescriptor> activeRecipeDescriptors, Collection<NamedStyles> availableStyles) {
        getLog().info("Available Recipes:");
        for (RecipeDescriptor recipeDescriptor : availableRecipeDescriptors) {
            writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
        }

        getLog().info("");
        getLog().info("Available Styles:");
        for (NamedStyles style : availableStyles) {
            getLog().info("    " + style.getName());
        }

        getLog().info("");
        getLog().info("Active Styles:");
        for (String activeStyle : activeStyles) {
            getLog().info("    " + activeStyle);
        }

        getLog().info("");
        getLog().info("Active Recipes:");
        for (RecipeDescriptor recipeDescriptor : activeRecipeDescriptors) {
            writeRecipeDescriptor(recipeDescriptor, detail, 0, 1);
        }

        getLog().info("");
        getLog().info("Found " + availableRecipeDescriptors.size() + " available recipes and " + availableStyles.size() + " available styles.");
        getLog().info("Configured with " + activeRecipeDescriptors.size() + " active recipes and " + activeStyles.size() + " active styles.");
    }

    private void writeRecipeDescriptor(RecipeDescriptor rd, boolean verbose, int currentRecursionLevel, int indentLevel) {
        String indent = StringUtils.repeat("    ", indentLevel * 4);
        if (currentRecursionLevel <= recursion) {
            if (verbose) {

                getLog().info(indent + rd.getDisplayName());
                getLog().info(indent + "    " + rd.getName());
                if (!rd.getDescription().isEmpty()) {
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
