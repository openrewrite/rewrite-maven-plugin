package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;
import org.openrewrite.internal.lang.Nullable;

import java.util.Collection;

/**
 * Display available recipes found on the classpath.<br>
 * {@code ./mvnw rewrite:discover -Drecipe=<recipe-name>} to display recipe configuration details. For example:<br>
 * {@code ./mvnw rewrite:discover -Drecipe=org.openrewrite.java.format.AutoFormat}
 */
@Mojo(name = "discover", threadSafe = true)
public class RewriteDiscoverMojo extends AbstractRewriteMojo {

    /**
     * The name of a specific recipe to show details for. For example:<br>
     * {@code ./mvnw rewrite:discover -Drecipe=org.openrewrite.java.format.AutoFormat}
     */
    @Nullable
    @Parameter(property = "recipe")
    String recipeFilter;

    /**
     * Whether to show verbose details of recipes and options.
     */
    @Parameter(property = "rewrite.discover.verbose", defaultValue = "false")
    boolean verbose;

    /**
     * Whether to show recipe details for recipes which use other recipes.
     */
    @Parameter(property = "rewrite.discover.recursive", defaultValue = "false")
    boolean recursive;

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();

        if (recipeFilter != null) {
            RecipeDescriptor recipeDescriptor = env.listRecipeDescriptors().stream().filter(r -> r.getName().equalsIgnoreCase(recipeFilter)).findAny().orElse(null);
            if (recipeDescriptor == null) {
                getLog().info("Recipe " + recipeFilter + " not found.");
            } else {
                logRecipeDescriptor(recipeDescriptor, verbose, recursive);
            }
            return;
        }
        Collection<Recipe> recipesByName = env.listRecipes();
        getLog().info("Found " + activeRecipes.size() + " active recipes and " + recipesByName.size() + " activatable recipes.");
        getLog().info("");

        getLog().info("Active Recipes:");
        for (String activeRecipe : activeRecipes) {
            getLog().info(indent(1, activeRecipe));
        }
        getLog().info("");
        getLog().info("Activatable Recipes:");
        for (Recipe recipe : recipesByName) {
            getLog().info(indent(1, recipe.getName()));
        }
        getLog().info("");
        if (verbose) {
            getLog().info("Descriptors:");
            for (RecipeDescriptor recipeDescriptor : env.listRecipeDescriptors()) {
                logRecipeDescriptor(recipeDescriptor, verbose, recursive);
            }
        }
    }

    private void logRecipeDescriptor(RecipeDescriptor recipeDescriptor, boolean verbose, boolean recursive) {
        if (verbose) {
            getLog().info(indent(1, "Name: " + recipeDescriptor.getName()));
            getLog().info(indent(1, "Display name: " + recipeDescriptor.getDisplayName()));
            getLog().info(indent(1, "Description: " + recipeDescriptor.getDescription()));
            if (!recipeDescriptor.getTags().isEmpty()) {
                getLog().info(indent(1, "Tags: " + String.join(",", recipeDescriptor.getTags())));
            }
        } else {
            getLog().info(indent(1, recipeDescriptor.getName()));
        }
        if (!recipeDescriptor.getOptions().isEmpty()) {
            if (verbose) {
                getLog().info(indent(1, "Options:"));
            }
            for (OptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                StringBuilder optionBuilder = new StringBuilder(optionDescriptor.getName())
                        .append(": ").append(optionDescriptor.getType());
                if (optionDescriptor.isRequired()) {
                    optionBuilder.append("!");
                }
                getLog().info(indent(2, optionBuilder));
                if (verbose) {
                    getLog().info(indent(2, "Display name: " + optionDescriptor.getDisplayName()));
                    getLog().info(indent(2, "Description: " + optionDescriptor.getDescription()));
                    getLog().info("");
                }
            }
        }
        if (!recipeDescriptor.getRecipeList().isEmpty()) {
            if (verbose) {
                getLog().info(indent(1, "Recipe list:"));
            }
            for (RecipeDescriptor r : recipeDescriptor.getRecipeList()) {
                logNestedRecipeDescriptor(r, verbose, recursive, 2);
            }
            if (verbose) {
                getLog().info("");
            }
        }
        if (!verbose || (recipeDescriptor.getOptions().isEmpty() && recipeDescriptor.getRecipeList().isEmpty())) {
            getLog().info("");
        }
    }

    private void logNestedRecipeDescriptor(RecipeDescriptor recipeDescriptor, boolean verbose, boolean recursive, int indent) {
        if (verbose) {
            getLog().info(indent(indent, "Name: " + recipeDescriptor.getName()));
            getLog().info(indent(indent, "Display name: " + recipeDescriptor.getDisplayName()));
            getLog().info(indent(indent, "Description: " + recipeDescriptor.getDescription()));
            if (!recipeDescriptor.getTags().isEmpty()) {
                getLog().info(indent(indent, "Tags: " + String.join(",", recipeDescriptor.getTags())));
            }
        } else {
            getLog().info(indent(indent, recipeDescriptor.getName()));
        }
        if (!recipeDescriptor.getOptions().isEmpty()) {
            if (verbose) {
                getLog().info(indent(indent, "Options:"));
            }
            for (OptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                getLog().info(indent(indent + 1, optionDescriptor.getName() + ": " + optionDescriptor.getValue()));
            }
            if (verbose) {
                getLog().info("");
            }
        }
        if (recursive && !recipeDescriptor.getRecipeList().isEmpty()) {
            if (verbose) {
                getLog().info(indent(indent, "Recipe list:"));
            }
            for (RecipeDescriptor nestedRecipeDescriptor : recipeDescriptor.getRecipeList()) {
                logNestedRecipeDescriptor(nestedRecipeDescriptor, verbose, true, indent + 1);
            }
            if (verbose) {
                getLog().info("");
            }
        }
        if (!verbose || (recipeDescriptor.getOptions().isEmpty() && recipeDescriptor.getRecipeList().isEmpty())) {
            getLog().info("");
        }
    }
}
