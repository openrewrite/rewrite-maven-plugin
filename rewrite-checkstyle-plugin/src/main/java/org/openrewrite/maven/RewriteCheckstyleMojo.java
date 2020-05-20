package org.openrewrite.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

@Mojo(name = "rewrite-checkstyle")
public class RewriteCheckstyleMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", property = "directory", required = true)
    private File directory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

    }
}
