package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.openrewrite.Recipe;
import org.openrewrite.config.*;

import java.util.Collection;

@Mojo(name = "discover", threadSafe = true)
public class RewriteDiscoverMojo extends AbstractRewriteMojo {
    private final Log log = getLog();

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
                log.info("Recipe " + recipeFilter + " not found.");
            } else {
                logRecipeDescriptor(recipeDescriptor, verbose, recursive);
            }
            return;
        }
        Collection<Recipe> recipesByName = env.listRecipes();
        log.info("Found " + activeRecipes.size() + " active recipes and " + recipesByName.size() + " activatable recipes.");
        log.info("");

        log.info("Active Recipes:");
        for (String activeRecipe : activeRecipes) {
            log.info("    " + activeRecipe);
        }
        log.info("");
        log.info("Activatable Recipes:");
        for (Recipe recipe : recipesByName) {
            log.info("    " + recipe.getName());
        }
        log.info("");
        log.info("Descriptors:");
        for (RecipeDescriptor recipeDescriptor : env.listRecipeDescriptors()) {
            logRecipeDescriptor(recipeDescriptor, verbose, recursive);
        }
    }

    private void logRecipeDescriptor(RecipeDescriptor recipeDescriptor, boolean verbose, boolean recursive) {
        if (verbose) {
            log.info("    Name: " + recipeDescriptor.getName());
            log.info("    Display name: " + recipeDescriptor.getDisplayName());
            log.info("    Description: " + recipeDescriptor.getDescription());
            if (!recipeDescriptor.getTags().isEmpty()) {
                log.info("    Tags: " + String.join(",", recipeDescriptor.getTags()));
            }
        } else {
            log.info("    " + recipeDescriptor.getName());
        }
        if (!recipeDescriptor.getOptions().isEmpty()) {
            if (verbose) {
                log.info("    Options:");
            }
            for (OptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                StringBuilder optionBuilder = new StringBuilder(optionDescriptor.getName())
                        .append(": ").append(optionDescriptor.getType());
                if (optionDescriptor.isRequired()) {
                    optionBuilder.append("!");
                }
                log.info("        " + optionBuilder.toString());
                if (verbose) {
                    log.info("        Display name: " + optionDescriptor.getDisplayName());
                    log.info("        Description: " + optionDescriptor.getDescription());
                    log.info("");
                }
            }
        }
        if (!recipeDescriptor.getRecipeList().isEmpty()) {
            if (verbose) {
                log.info("    Recipe list:");
            }
            for (RecipeDescriptor r : recipeDescriptor.getRecipeList()) {
                logNestedRecipeDescriptor(r, verbose, recursive, "        ");
            }
            if (verbose) {
                log.info("");
            }
        }
        if (!verbose || (recipeDescriptor.getOptions().isEmpty() && recipeDescriptor.getRecipeList().isEmpty())) {
            log.info("");
        }
    }

    private void logNestedRecipeDescriptor(RecipeDescriptor recipeDescriptor, boolean verbose, boolean recursive, String indent) {
        if (verbose) {
            log.info(indent + "Name: " + recipeDescriptor.getName());
            log.info(indent + "Display name: " + recipeDescriptor.getDisplayName());
            log.info(indent + "Description: " + recipeDescriptor.getDescription());
            if (!recipeDescriptor.getTags().isEmpty()) {
                log.info(indent + "Tags: " + String.join(",", recipeDescriptor.getTags()));
            }
        } else {
            log.info(indent + recipeDescriptor.getName());
        }
        if (!recipeDescriptor.getOptions().isEmpty()) {
            if (verbose) {
                log.info(indent + "Options:");
            }
            for (OptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                log.info(indent + "    " + optionDescriptor.getName() + ": " + optionDescriptor.getValue());
            }
            if (verbose) {
                log.info("");
            }
        }
        if (recursive && !recipeDescriptor.getRecipeList().isEmpty()) {
            if (verbose) {
                log.info(indent + "Recipe list:");
            }
            for (RecipeDescriptor nestedRecipeDescriptor : recipeDescriptor.getRecipeList()) {
                logNestedRecipeDescriptor(nestedRecipeDescriptor, verbose, true, indent + "    ");
            }
            if (verbose) {
                log.info("");
            }
        }
        if (!verbose || (recipeDescriptor.getOptions().isEmpty() && recipeDescriptor.getRecipeList().isEmpty())) {
            log.info("");
        }
    }
}
