package org.openrewrite.maven;

import org.openrewrite.marker.Marker;

import java.util.UUID;

public class GeneratedSourceMarker implements Marker {
    private final UUID id;

    public GeneratedSourceMarker(UUID id) {
        this.id = id;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
