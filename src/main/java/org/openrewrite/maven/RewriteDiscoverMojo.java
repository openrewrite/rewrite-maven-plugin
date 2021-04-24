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
            getLog().info("    " + activeRecipe);
        }
        getLog().info("");
        getLog().info("Activatable Recipes:");
        for (Recipe recipe : recipesByName) {
            getLog().info("    " + recipe.getName());
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
            getLog().info("    Name: " + recipeDescriptor.getName());
            getLog().info("    Display name: " + recipeDescriptor.getDisplayName());
            getLog().info("    Description: " + recipeDescriptor.getDescription());
            if (!recipeDescriptor.getTags().isEmpty()) {
                getLog().info("    Tags: " + String.join(",", recipeDescriptor.getTags()));
            }
        } else {
            getLog().info("    " + recipeDescriptor.getName());
        }
        if (!recipeDescriptor.getOptions().isEmpty()) {
            if (verbose) {
                getLog().info("    Options:");
            }
            for (OptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                StringBuilder optionBuilder = new StringBuilder(optionDescriptor.getName())
                        .append(": ").append(optionDescriptor.getType());
                if (optionDescriptor.isRequired()) {
                    optionBuilder.append("!");
                }
                getLog().info("        " + optionBuilder);
                if (verbose) {
                    getLog().info("        Display name: " + optionDescriptor.getDisplayName());
                    getLog().info("        Description: " + optionDescriptor.getDescription());
                    getLog().info("");
                }
            }
        }
        if (!recipeDescriptor.getRecipeList().isEmpty()) {
            if (verbose) {
                getLog().info("    Recipe list:");
            }
            for (RecipeDescriptor r : recipeDescriptor.getRecipeList()) {
                logNestedRecipeDescriptor(r, verbose, recursive, "        ");
            }
            if (verbose) {
                getLog().info("");
            }
        }
        if (!verbose || (recipeDescriptor.getOptions().isEmpty() && recipeDescriptor.getRecipeList().isEmpty())) {
            getLog().info("");
        }
    }

    private void logNestedRecipeDescriptor(RecipeDescriptor recipeDescriptor, boolean verbose, boolean recursive, String indent) {
        if (verbose) {
            getLog().info(indent + "Name: " + recipeDescriptor.getName());
            getLog().info(indent + "Display name: " + recipeDescriptor.getDisplayName());
            getLog().info(indent + "Description: " + recipeDescriptor.getDescription());
            if (!recipeDescriptor.getTags().isEmpty()) {
                getLog().info(indent + "Tags: " + String.join(",", recipeDescriptor.getTags()));
            }
        } else {
            getLog().info(indent + recipeDescriptor.getName());
        }
        if (!recipeDescriptor.getOptions().isEmpty()) {
            if (verbose) {
                getLog().info(indent + "Options:");
            }
            for (OptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                getLog().info(indent + "    " + optionDescriptor.getName() + ": " + optionDescriptor.getValue());
            }
            if (verbose) {
                getLog().info("");
            }
        }
        if (recursive && !recipeDescriptor.getRecipeList().isEmpty()) {
            if (verbose) {
                getLog().info(indent + "Recipe list:");
            }
            for (RecipeDescriptor nestedRecipeDescriptor : recipeDescriptor.getRecipeList()) {
                logNestedRecipeDescriptor(nestedRecipeDescriptor, verbose, true, indent + "    ");
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
