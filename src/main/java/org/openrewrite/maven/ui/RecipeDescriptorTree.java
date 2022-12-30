package org.openrewrite.maven.ui;

import org.openrewrite.config.RecipeDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Used for traversing around the collection of available {@linkplain RecipeDescriptor}s in the environment.
 * <p>
 * It's not the most "proper" tree in the world, since these hold both data for a "directory" of recipes as well
 * as RecipeDescriptors themselves. But it's not the end of the world.
 */
public class RecipeDescriptorTree implements Comparable<RecipeDescriptorTree> {
    private static final Pattern PATH_SEPARATOR = Pattern.compile("\\.");

    private String displayName;
    private RecipeDescriptorTree parent;
    private final List<RecipeDescriptorTree> children = new ArrayList<>();
    private RecipeDescriptor recipeDescriptor;

    public RecipeDescriptorTree() {
        this(null);
    }

    public RecipeDescriptorTree(String displayName) {
        this.displayName = displayName;
        this.parent = null;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public RecipeDescriptorTree addChild(RecipeDescriptorTree child) {
        child.parent = this;
        this.children.add(child);
        return child;
    }

    public RecipeDescriptorTree addChild(String displayName) {
        return addChild(new RecipeDescriptorTree(displayName));
    }

    public List<RecipeDescriptorTree> getChildren() {
        return this.children;
    }

    public RecipeDescriptorTree getParent() {
        return this.parent;
    }

    public RecipeDescriptor getRecipeDescriptor() {
        return this.recipeDescriptor;
    }

    public void setRecipeDescriptor(RecipeDescriptor recipeDescriptor) {
        this.recipeDescriptor = recipeDescriptor;
        this.displayName = recipeDescriptor.getDisplayName();
    }

    public void addPath(RecipeDescriptor path) {
        String[] names = PATH_SEPARATOR.split(path.getName());
        RecipeDescriptorTree tree = this;
        for (String name : names) {
            if (tree.children.stream().anyMatch(r -> r.displayName.equalsIgnoreCase(name))) {
                tree = tree.children.stream().filter(r -> r.displayName.equalsIgnoreCase(name)).findFirst().get();
            } else {
                tree = tree.addChild(name);
            }
        }

        // we've traversed to a leaf node in the tree, which means we can tack on the
        // RecipeDescriptor to this node and know it's a leaf node instead of a directory placeholder.
        tree.setRecipeDescriptor(path);
    }

    public void addPath(Iterable<RecipeDescriptor> path) {
        for (RecipeDescriptor rd : path) {
            addPath(rd);
        }
    }

    @Override
    public int compareTo(RecipeDescriptorTree o) {
        return this.children.size() - o.children.size();
    }
}
