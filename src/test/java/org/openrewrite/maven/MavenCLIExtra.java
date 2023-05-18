package org.openrewrite.maven;

final class MavenCLIExtra
{
    /**
     * User property to mute Maven 3.9.2+ "plugin validation" warnings.
     */
    static final String MUTE_PLUGIN_VALIDATION_WARNING = "-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugin.internal.DefaultPluginValidationManager=off";
}
