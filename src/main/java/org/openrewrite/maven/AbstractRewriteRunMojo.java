/*
 * Copyright 2021 the original author or authors.
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
import org.openrewrite.FileAttributes;
import org.openrewrite.RecipeRunException;
import org.openrewrite.Result;
import org.openrewrite.binary.Binary;
import org.openrewrite.ipc.http.HttpUrlConnectionSender;
import org.openrewrite.quark.Quark;
import org.openrewrite.remote.Remote;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Run the configured recipes and apply the changes locally.
 * <p>
 * Base mojo for rewrite:run and rewrite:runNoFork.
 */
public class AbstractRewriteRunMojo extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException {

        //If the plugin is configured to run over all projects (at the end of the build) only proceed if the plugin
        //is being run on the last project.
        if (!runPerSubmodule && !project.getId().equals(mavenSession.getProjects().get(mavenSession.getProjects().size() - 1).getId())) {
            return;
        }

        ResultsContainer results = listResults();
        RecipeRunException firstException = results.getFirstException();
        if (firstException != null) {
            getLog().error("The recipe produced an error. Please report this to the recipe author.");
            throw firstException;
        }

        if (results.isNotEmpty()) {
            for (Result result : results.generated) {
                assert result.getAfter() != null;
                getLog().warn("Generated new file " +
                              result.getAfter().getSourcePath().normalize() +
                              " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.deleted) {
                assert result.getBefore() != null;
                getLog().warn("Deleted file " +
                              result.getBefore().getSourcePath().normalize() +
                              " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.moved) {
                assert result.getAfter() != null;
                assert result.getBefore() != null;
                getLog().warn("File has been moved from " +
                              result.getBefore().getSourcePath().normalize() + " to " +
                              result.getAfter().getSourcePath().normalize() + " by:");
                logRecipesThatMadeChanges(result);
            }
            for (Result result : results.refactoredInPlace) {
                assert result.getBefore() != null;
                getLog().warn("Changes have been made to " +
                              result.getBefore().getSourcePath().normalize() +
                              " by:");
                logRecipesThatMadeChanges(result);
            }

            getLog().warn("Please review and commit the results.");

            try {
                for (Result result : results.generated) {
                    assert result.getAfter() != null;
                    writeAfter(results.getProjectRoot(), result);
                }
                for (Result result : results.deleted) {
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath()).normalize();
                    boolean deleteSucceeded = originalLocation.toFile().delete();
                    if (!deleteSucceeded) {
                        throw new IOException("Unable to delete file " + originalLocation.toAbsolutePath());
                    }
                }
                for (Result result : results.moved) {
                    // Should we try to use git to move the file first, and only if that fails fall back to this?
                    assert result.getBefore() != null;
                    Path originalLocation = results.getProjectRoot().resolve(result.getBefore().getSourcePath());
                    File originalParentDir = originalLocation.toFile().getParentFile();

                    assert result.getAfter() != null;
                    // Ensure directories exist in case something was moved into a hitherto non-existent package
                    Path afterLocation = results.getProjectRoot().resolve(result.getAfter().getSourcePath());
                    File afterParentDir = afterLocation.toFile().getParentFile();
                    // Rename the directory if its name case has been changed, e.g. camel case to lower case.
                    if (afterParentDir.exists()
                        && afterParentDir.getAbsolutePath().equalsIgnoreCase((originalParentDir.getAbsolutePath()))
                        && !afterParentDir.getAbsolutePath().equals(originalParentDir.getAbsolutePath())) {
                        if (!originalParentDir.renameTo(afterParentDir)) {
                            throw new RuntimeException("Unable to rename directory from " + originalParentDir.getAbsolutePath() + " To: " + afterParentDir.getAbsolutePath());
                        }
                    } else if (!afterParentDir.exists() && !afterParentDir.mkdirs()) {
                        throw new RuntimeException("Unable to create directory " + afterParentDir.getAbsolutePath());
                    }
                    if (result.getAfter() instanceof Quark) {
                        // We don't know the contents of a Quark, but we can move it
                        Files.move(originalLocation, results.getProjectRoot().resolve(result.getAfter().getSourcePath()));
                    } else {
                        // On Mac this can return "false" even when the file was deleted, so skip the check
                        //noinspection ResultOfMethodCallIgnored
                        originalLocation.toFile().delete();
                        writeAfter(results.getProjectRoot(), result);
                    }
                }
                for (Result result : results.refactoredInPlace) {
                    assert result.getBefore() != null;
                    assert result.getAfter() != null;
                    writeAfter(results.getProjectRoot(), result);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to rewrite source files", e);
            }
        }
    }

    private static void writeAfter(Path root, Result result) {
        assert result.getAfter() != null;
        Path targetPath = root.resolve(result.getAfter().getSourcePath());
        File targetFile = targetPath.toFile();
        if (!targetFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetFile.getParentFile().mkdirs();
        }
        if (result.getAfter() instanceof Binary) {
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                sourceFileWriter.write(((Binary) result.getAfter()).getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else if (result.getAfter() instanceof Remote) {
            Remote remote = (Remote) result.getAfter();
            try (FileOutputStream sourceFileWriter = new FileOutputStream(targetFile)) {
                InputStream source = remote.getInputStream(new HttpUrlConnectionSender());
                byte[] buf = new byte[4096];
                int length;
                while ((length = source.read(buf)) > 0) {
                    sourceFileWriter.write(buf, 0, length);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        } else {
            Charset charset = result.getAfter().getCharset() == null ? StandardCharsets.UTF_8 : result.getAfter().getCharset();
            try (BufferedWriter sourceFileWriter = Files.newBufferedWriter(targetPath, charset)) {
                sourceFileWriter.write(new String(result.getAfter().printAll(new SanitizedMarkerPrinter()).getBytes(charset), charset));
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to rewrite source files", e);
            }
        }
        if (result.getAfter().getFileAttributes() != null) {
            FileAttributes fileAttributes = result.getAfter().getFileAttributes();
            if (targetFile.canRead() != fileAttributes.isReadable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setReadable(fileAttributes.isReadable());
            }
            if (targetFile.canWrite() != fileAttributes.isWritable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setWritable(fileAttributes.isWritable());
            }
            if (targetFile.canExecute() != fileAttributes.isExecutable()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.setExecutable(fileAttributes.isExecutable());
            }
        }
    }
}
