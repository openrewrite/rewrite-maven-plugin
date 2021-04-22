package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.OptionDescriptor;
import org.openrewrite.config.RecipeDescriptor;

import java.util.Collection;

/**
 * Generate a list showing the available and applied recipes based on what Rewrite finds on your classpath.
 */
@Mojo(name = "discover", threadSafe = true)
public class RewriteDiscoverMojo extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();

        String recipeFilter = System.getProperty("rewrite.discover.recipe");
        String verboseProperty = System.getProperty("rewrite.discover.verbose");
        boolean verbose = Boolean.parseBoolean(verboseProperty);
        String recursiveProperty = System.getProperty("rewrite.discover.recursive");
        boolean recursive = Boolean.parseBoolean(recursiveProperty);

        if (recipeFilter != null) {
            RecipeDescriptor recipeDescriptor = env.listRecipeDescriptors().stream().filter(r -> r.getName().equals(recipeFilter)).findAny().orElse(null);
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
