package org.openrewrite.maven;

import org.apache.maven.plugins.annotations.Parameter;
import org.openrewrite.config.ProfileConfiguration;

import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toMap;

public class MavenProfileConfiguration {
    @Parameter(property = "name", defaultValue = "default")
    String name;

    @Parameter(property = "include")
    private Set<String> include;

    @Parameter(property = "exclude")
    private Set<String> exclude;

    @Parameter(property = "extend")
    private Set<String> extend;

    @Parameter(property = "configure")
    List<MavenProfileProperty> configure;

    public ProfileConfiguration toProfileConfiguration() {
        ProfileConfiguration profile = new ProfileConfiguration();
        if(name != null) {
            profile.setName(name);
        }
        if(include != null) {
            profile.setInclude(include);
        }
        if(exclude != null) {
            profile.setExclude(exclude);
        }
        if(extend != null) {
            profile.setExtend(extend);
        }
        if(configure != null) {
            profile.setConfigure(configure.stream()
                    .collect(toMap(prop -> prop.visitor + "." + prop.key, prop -> prop.value)));
        }
        return profile;
    }
}
