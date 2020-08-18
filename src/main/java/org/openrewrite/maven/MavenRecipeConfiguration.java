package org.openrewrite.maven;

import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.config.RecipeConfiguration;

import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toMap;

public class MavenRecipeConfiguration {
    @Parameter(property = "name", defaultValue = "default")
    String name;

    @Parameter(property = "include")
    private Set<String> include;

    @Parameter(property = "exclude")
    private Set<String> exclude;

    @Parameter(property = "configure")
    List<MavenRecipeProperty> configure;

    public RecipeConfiguration toRecipeConfiguration() {
        RecipeConfiguration recipe = new RecipeConfiguration();
        if(name != null) {
            recipe.setName(name);
        }
        if(include != null) {
            recipe.setInclude(include);
        }
        if(exclude != null) {
            recipe.setExclude(exclude);
        }
        if(configure != null) {
            recipe.setConfigure(configure.stream()
                    .collect(toMap(prop -> prop.visitor + "." + prop.key, prop -> prop.value)));
        }
        return recipe;
    }
}
