package org.openrewrite.maven;

import org.apache.maven.DefaultMaven;
import org.apache.maven.Maven;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;

class RewriteWarnMojoTest extends AbstractMojoTestCase {
    private static final String TEST_POM = "/unit/single-project/pom.xml";

    private RewriteWarnMojo testMojo;

    @Test
    void checkstyle() throws Exception {
        testMojo.activeProfiles = "checkstyle";
        testMojo.configLocation = "rewrite.yml";
        testMojo.execute();
    }

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        URL url = getClass().getResource(TEST_POM);
        if (url == null) {
            throw new MojoExecutionException(String.format("Cannot locate %s", TEST_POM));
        }

        File pom = new File(url.getFile());
        Settings settings = getMavenSettings();
        if (settings.getLocalRepository() == null) {
            settings.setLocalRepository(
                    org.apache.maven.repository.RepositorySystem
                            .defaultUserLocalRepository.getAbsolutePath());
        }

        MavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setPom(pom);
        ArtifactRepository artifactRepository =
                new org.apache.maven.artifact.repository.
                        DefaultArtifactRepository("id", settings.getLocalRepository(), new DefaultRepositoryLayout());
        request.setLocalRepository(artifactRepository);

        MavenExecutionRequestPopulator populator = getContainer().lookup(MavenExecutionRequestPopulator.class);
        populator.populateFromSettings(request, settings);

        DefaultMaven maven = (DefaultMaven) getContainer().lookup(Maven.class);
        DefaultRepositorySystemSession repositorySystemSession = (DefaultRepositorySystemSession)
                maven.newRepositorySession(request);

        LocalRepositoryManager localRepositoryManager = new SimpleLocalRepositoryManagerFactory()
                .newInstance(repositorySystemSession, new LocalRepository(settings.getLocalRepository()));
        repositorySystemSession.setLocalRepositoryManager(localRepositoryManager);

        ProjectBuildingRequest buildingRequest =
                request.getProjectBuildingRequest()
                        .setRepositorySession(repositorySystemSession)
                        .setResolveDependencies(true);

        ProjectBuilder projectBuilder = lookup(ProjectBuilder.class);
        ProjectBuildingResult projectBuildingResult =
                projectBuilder.build(pom, buildingRequest);
        MavenProject project = projectBuildingResult.getProject();
        MavenSession session = new MavenSession(getContainer(),
                repositorySystemSession, request,
                new DefaultMavenExecutionResult());
        session.setCurrentProject(project);
        session.setProjects(singletonList(project));
        request.setSystemProperties(System.getProperties());

        testMojo = (RewriteWarnMojo) lookupConfiguredMojo(session, newMojoExecution("warn"));
        testMojo.getLog().debug(String.format("localRepo = %s", request.getLocalRepository()));
    }

    private Settings getMavenSettings() throws ComponentLookupException, IOException,
            XmlPullParserException {
        org.apache.maven.settings.MavenSettingsBuilder mavenSettingsBuilder = (org.apache.maven.settings.MavenSettingsBuilder)
                getContainer().lookup(org.apache.maven.settings.MavenSettingsBuilder.ROLE);
        return mavenSettingsBuilder.buildSettings();
    }
}
