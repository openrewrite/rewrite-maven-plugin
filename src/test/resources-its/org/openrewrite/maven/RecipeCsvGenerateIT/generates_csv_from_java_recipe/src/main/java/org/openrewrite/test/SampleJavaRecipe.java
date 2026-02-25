package org.openrewrite.test;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;

public class SampleJavaRecipe extends Recipe {

    @Override
    public String getDisplayName() {
        return "Sample Java recipe";
    }

    @Override
    public String getDescription() {
        return "A sample imperative recipe for testing CSV generation.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return TreeVisitor.noop();
    }
}
