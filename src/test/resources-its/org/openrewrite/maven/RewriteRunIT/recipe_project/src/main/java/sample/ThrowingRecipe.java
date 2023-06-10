package sample;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.tree.J;

import java.nio.file.*;
import java.util.Random;

public class ThrowingRecipe extends Recipe {

    @Override
    public String getDisplayName() {
        return "Throws exception";
    }

    @Override
    public String getDescription() {
        return "Throws exception.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                throw new RuntimeException("This recipe throws an exception.");
            }
        };
    }
}
