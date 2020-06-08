package org.openrewrite.maven;

import org.junit.jupiter.api.Test;
import org.openrewrite.Profile;
import org.openrewrite.checkstyle.CovariantEquals;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

class AbstractRewriteMojoTest {
    @Test
    void profileXml() {
        AbstractRewriteMojo.MavenProfileConfiguration config = new AbstractRewriteMojo.MavenProfileConfiguration();
        config.name = "default";

        AbstractRewriteMojo.MavenProfileProperty prop = new AbstractRewriteMojo.MavenProfileProperty();
        prop.visitor = "org.openrewrite.checkstyle.*";
        prop.key = "config";
        prop.value = "<?xml version=\"1.0\"?>\n" +
                "<!DOCTYPE module PUBLIC\n" +
                "  \"-//Checkstyle//DTD Checkstyle Configuration 1.3//EN\"\n" +
                "  \"https://checkstyle.org/dtds/configuration_1_3.dtd\">\n" +
                "<module name=\"Checker\">\n" +
                "  <module name=\"TreeWalker\">\n" +
                "    <module name=\"CovariantEquals\"/>\n" +
                "  </module>\n" +
                "</module>";

        config.configure = singletonList(prop);

        Profile profile = config.toProfileConfiguration().build(emptyList());
        CovariantEquals covariantEquals = new CovariantEquals();
        profile.configure(profile.configure(covariantEquals));

        assertThat(covariantEquals.validate().isValid()).isTrue();
    }
}
