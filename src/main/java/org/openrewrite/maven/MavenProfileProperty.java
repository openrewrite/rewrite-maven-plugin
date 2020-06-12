package org.openrewrite.maven;

import org.apache.maven.plugins.annotations.Parameter;

public class MavenProfileProperty {
    @Parameter(property = "visitor", required = true)
    String visitor;

    @Parameter(property = "key", required = true)
    String key;

    @Parameter(property = "value", required = true)
    String value;
}
