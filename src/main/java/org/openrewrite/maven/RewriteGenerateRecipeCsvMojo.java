/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.openrewrite.marketplace.RecipeMarketplace;
import org.openrewrite.marketplace.RecipeMarketplaceReader;
import org.openrewrite.marketplace.RecipeMarketplaceWriter;
import org.openrewrite.maven.marketplace.MavenRecipeMarketplaceGenerator;
import org.openrewrite.maven.tree.GroupArtifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Generate a {@code recipes.csv} marketplace file from the recipes found in this project.
 * Scans compiled classes and resources in {@code target/classes/} for recipe definitions
 * (both Java class-based and YAML declarative recipes) and writes the result to
 * {@code src/main/resources/META-INF/rewrite/recipes.csv}.
 * <p>
 * If an existing {@code recipes.csv} is present, generated data is merged into it,
 * preserving any manually added entries.
 * <p>
 * {@code ./mvnw rewrite:generateRecipeCsv}
 */
@Execute(phase = LifecyclePhase.PROCESS_CLASSES)
@Mojo(name = "generateRecipeCsv", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class RewriteGenerateRecipeCsvMojo extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException {
        if (rewriteSkip) {
            getLog().info("Skipping execution");
            return;
        }

        if ("pom".equals(project.getPackaging())) {
            getLog().info("Skipping pom-packaging project");
            return;
        }

        Path classesDir = Paths.get(project.getBuild().getOutputDirectory());
        if (!Files.exists(classesDir)) {
            getLog().warn("No compiled classes found at " + classesDir + ". Skipping.");
            return;
        }

        String groupId = project.getGroupId();
        String artifactId = project.getArtifactId();

        List<Path> classpath;
        try {
            classpath = new ArrayList<>();
            for (String element : project.getRuntimeClasspathElements()) {
                Path p = Paths.get(element);
                if (!p.equals(classesDir)) {
                    classpath.add(p);
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to resolve runtime classpath", e);
        }

        getLog().info("Generating recipe CSV from: " + classesDir);
        getLog().info("Using GAV: " + groupId + ":" + artifactId);

        MavenRecipeMarketplaceGenerator generator = new MavenRecipeMarketplaceGenerator(
                new GroupArtifact(groupId, artifactId),
                classesDir,
                classpath
        );
        RecipeMarketplace generated = generator.generate();

        Path outputPath = project.getBasedir().toPath()
                .resolve("src/main/resources/META-INF/rewrite/recipes.csv");

        RecipeMarketplace marketplace = generated;
        if (Files.exists(outputPath)) {
            getLog().info("Found existing recipes.csv, merging...");
            RecipeMarketplaceReader reader = new RecipeMarketplaceReader();
            RecipeMarketplace existing = reader.fromCsv(outputPath);
            existing.getRoot().merge(generated.getRoot());
            marketplace = existing;
        }

        RecipeMarketplaceWriter writer = new RecipeMarketplaceWriter();
        String csv = writer.toCsv(marketplace);

        // Skip if CSV has only a header line (no actual recipes)
        int lineCount = 0;
        for (int i = 0; i < csv.length(); i++) {
            if (csv.charAt(i) == '\n') {
                lineCount++;
            }
        }
        if (lineCount <= 1) {
            getLog().info("No recipes found, skipping recipes.csv generation");
            return;
        }

        try {
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, csv.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write recipes.csv", e);
        }

        getLog().info("Generated recipes.csv at: " + outputPath.toAbsolutePath());
    }
}
