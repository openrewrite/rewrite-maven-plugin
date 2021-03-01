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

        Collection<Recipe> recipesByName = env.listRecipes();
        log.info("Found " + activeRecipes.size() + " active recipes and " + recipesByName.size() + " total recipes.");
        log.info("");

        log.info("Active Recipes:");
        for (String activeRecipe : activeRecipes) {
            log.info("    " + activeRecipe);
        }
        log.info("");
        log.info("All Recipes:");
        for (Recipe recipe : recipesByName) {
            log.info("    name: " + recipe.getName());
        }
        log.info("");
        log.info("Recipe Descriptors:");
        for (RecipeDescriptor recipeDescriptor : env.listRecipeDescriptors()) {
            log.info("    Recipe name: " + recipeDescriptor.getName());
            log.info("    Recipe display name: " + recipeDescriptor.getDisplayName());
            log.info("    Recipe description: " + recipeDescriptor.getDescription());
            if (!recipeDescriptor.getTags().isEmpty()) {
                StringBuilder tags = new StringBuilder();
                recipeDescriptor.getTags().forEach(t -> tags.append(t).append(", "));
                tags.delete(tags.length() - 1, tags.length());
                log.info("    Recipe tags: " + tags.toString());
            }
            if (!recipeDescriptor.getOptions().isEmpty()) {
                log.info("    Options:");
                for (OptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                    log.info("        Option name: " + optionDescriptor.getName());
                    log.info("        Option type: " + optionDescriptor.getType());
                    log.info("        Option display name: " + optionDescriptor.getDisplayName());
                    log.info("        Option description: " + optionDescriptor.getDescription());
                    log.info("");
                }
            }
            if (!recipeDescriptor.getRecipeList().isEmpty()) {
                log.info("    Recipe list:");
                for (ConfiguredRecipeDescriptor r : recipeDescriptor.getRecipeList()) {
                    printConfiguredRecipeDescriptor(r, "        ");
                }
            }
            if (recipeDescriptor.getOptions().isEmpty() && recipeDescriptor.getRecipeList().isEmpty()) {
                log.info("");
            }
        }
    }

    private void printConfiguredRecipeDescriptor(ConfiguredRecipeDescriptor recipeDescriptor, String indent) {
        log.info(indent + "Recipe name: " + recipeDescriptor.getName());
        log.info(indent + "Recipe display name: " + recipeDescriptor.getDisplayName());
        if (!recipeDescriptor.getOptions().isEmpty()) {
            log.info(indent + "Options:");
            for (ConfiguredOptionDescriptor optionDescriptor : recipeDescriptor.getOptions()) {
                log.info(indent + "    Option name: " + optionDescriptor.getName());
                log.info(indent + "    Option type: " + optionDescriptor.getType());
                if (optionDescriptor.getValue() != null) {
                    log.info(indent + "    Option value: " + optionDescriptor.getValue());
                }
                log.info("");
            }
        }
        if (!recipeDescriptor.getRecipeList().isEmpty()) {
            log.info(indent + "Recipe list:");
            for (ConfiguredRecipeDescriptor r : recipeDescriptor.getRecipeList()) {
                printConfiguredRecipeDescriptor(r, indent + "\t");
                log.info("");
            }
        }
        if (recipeDescriptor.getOptions().isEmpty() && recipeDescriptor.getRecipeList().isEmpty()) {
            log.info("");
        }
    }
}
