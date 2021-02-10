package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.openrewrite.config.Environment;
import org.openrewrite.Recipe;

import java.util.Collection;

@Mojo(name = "discover", threadSafe = true)
public class RewriteDiscoverMojo extends AbstractRewriteMojo {
    private final Log log = getLog();

    @Override
    public void execute() throws MojoExecutionException {
        Environment env = environment();

        Collection<Recipe> recipesByName = env.listRecipes();
        log.info("Found " + activeRecipes.size() + " active recipes and " + recipesByName.size() + " total recipes.\n");

        log.info("Active Recipe Names:");
        for (String activeRecipe : activeRecipes) {
            log.info("\t" + activeRecipe);
        }

        log.info("\nRecipes:");
        for (Recipe recipe : recipesByName) {
            log.info("\tname: " + recipe.getName());
        }
    }
}
