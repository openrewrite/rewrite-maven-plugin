package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.openrewrite.Environment;
import org.openrewrite.Recipe;

import java.util.Map;

@Mojo(name = "discover", threadSafe = true)
public class RewriteDiscoverMojo extends AbstractRewriteMojo {
    private final Log log = getLog();

    @Override
    public void execute() throws MojoExecutionException {
        Environment environment = environment();
        Map<String, Recipe> recipesByName = environment.getRecipesByName();

        log.info("Found " + activeRecipes.size() + " active recipes and " + recipesByName.size() + " total recipes.\n");

        log.info("Active Recipe Names:");
        activeRecipes.forEach(activeRecipe -> log.info("\t" +
                activeRecipe + "\n"));

        log.info("Recipes:");
        for (Recipe recipe : recipesByName.values()) {
            log.info("\tname: " + recipe.getName());
            log.info("\tinclude: ");
            recipe.getInclude().forEach(rec -> log.info("\t\t" +
                    rec.pattern()
                            .replace("\\", "")
                            .replace("[^.]+", "*")));
            log.info("\texclude: ");
            recipe.getExclude().forEach(rec -> log.info("\t\t" +
                    rec.pattern()
                            .replace("\\", "")
                            .replace("[^.]+", "*")));
            log.info("\tvisitors: ");
            environment.visitors(recipe.getName()).forEach(rec -> {
                log.info("\t\t" + rec.getName());
            });
            log.info("---");
            log.info("");
        }
    }
}
