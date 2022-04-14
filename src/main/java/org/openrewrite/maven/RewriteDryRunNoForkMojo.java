package org.openrewrite.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Generate warnings to the console for any recipe that would make changes, but do not make changes.
 *
 * This variant of rewrite:dryRun will not fork the maven life cycle and can be used (along with other goals) without
 * triggering repeated life-cycle events.
 */
@Mojo(name = "dryRunNoFork", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true,
        defaultPhase = LifecyclePhase.COMPILE)
public class RewriteDryRunNoForkMojo extends AbstractRewriteDryRunMojo {
}
