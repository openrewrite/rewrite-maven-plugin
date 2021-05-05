package org.openrewrite.maven.ui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.utils.AttributedString;
import org.openrewrite.config.RecipeDescriptor;

import java.util.*;
import java.util.function.Supplier;

public class RecipeDescriptorCompleter implements Completer {
    protected Collection<Candidate> candidates;
    protected Supplier<Collection<RecipeDescriptor>> recipeDescriptorSupplier;

    public RecipeDescriptorCompleter() {
        this(Collections.<Candidate>emptyList());
    }

    public RecipeDescriptorCompleter(Supplier<Collection<RecipeDescriptor>> recipeDescriptorSupplier) {
        assert recipeDescriptorSupplier != null;
        candidates = null;
        this.recipeDescriptorSupplier = recipeDescriptorSupplier;
    }

    public RecipeDescriptorCompleter(RecipeDescriptor... recipeDescriptors) {
        this(Arrays.asList(recipeDescriptors));
    }

    public RecipeDescriptorCompleter(Iterable<RecipeDescriptor> recipeDescriptors) {
        assert recipeDescriptors != null;
        this.candidates = new ArrayList<>();
        for (RecipeDescriptor recipeDescriptor : recipeDescriptors) {
            candidates.add(
                    new Candidate(
                            AttributedString.stripAnsi(recipeDescriptor.getName()),
                            recipeDescriptor.getDisplayName(),
                            getRecipePackageParent(recipeDescriptor),
                            recipeDescriptor.getDescription(),
                            null,
                            null,
                            true
                    )
            );
        }
    }

    public RecipeDescriptorCompleter(Candidate... candidates) {
        this(Arrays.asList(candidates));
    }

    public RecipeDescriptorCompleter(Collection<Candidate> candidates) {
        assert candidates != null;
        this.candidates = new ArrayList<>(candidates);
    }

    @Override
    public void complete(LineReader reader, final ParsedLine commandLine, final List<Candidate> candidates) {
        assert commandLine != null;
        assert candidates != null;
        if (this.candidates != null) {
            candidates.addAll(this.candidates);
        } else {
            for (RecipeDescriptor recipeDescriptor : recipeDescriptorSupplier.get()) {
                candidates.add(
                        new Candidate(
                                AttributedString.stripAnsi(recipeDescriptor.getName()),
                                recipeDescriptor.getDisplayName(),
                                getRecipePackageParent(recipeDescriptor),
                                recipeDescriptor.getDescription(),
                                null,
                                null,
                                true
                        )
                );
            }
        }
    }

    @Override
    public String toString() {
        String value = candidates != null ? candidates.toString() : "{" + recipeDescriptorSupplier.toString() + "}";
        return "RecipeDescriptorCompleter" + value;
    }

    public static String getRecipePackageParent(RecipeDescriptor recipe) {
        String recipePath = recipe.getName().toLowerCase();
        return recipePath.substring(0, recipePath.lastIndexOf("."));
    }
}