package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.RecipeDescriptor;

import java.util.Collection;

@Mojo(name = "discover", threadSafe = true)
public class RewriteDiscoverMojo extends AbstractRewriteMojo {
    private final Log log = getLog();

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();

        Collection<Recipe> recipesByName = env.listRecipes();
        log.info("Found " + activeRecipes.size() + " active recipes and " + recipesByName.size() + " total recipes.\n");

        log.info("Active Recipes:");
        for (String activeRecipe : activeRecipes) {
            log.info("\t" + activeRecipe);
        }
        log.info("");
        log.info("All Recipes:");
        for (Recipe recipe : recipesByName) {
            log.info("\tname: " + recipe.getName());
        }

        log.info("\nRecipe Descriptors:");
        for (RecipeDescriptor recipeDescriptor : env.listRecipeDescriptors()) {
            log.info("\tname: " + recipeDescriptor.getName());
            for (String parameter : recipeDescriptor.getParameters()) {
                log.info("\t\tparameter: " + parameter);
            }
        }
    }
}
