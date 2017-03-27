package com.salesforce.storm.spout.sideline.trigger;

import java.util.UUID;

public class SidelineRequestIdentifier {

    public final UUID id;

    public SidelineRequestIdentifier(final UUID id) {
        this.id = id;
    }

    public SidelineRequestIdentifier() {
        this(UUID.randomUUID());
    }

    /**
     * Override toString to return the id.
     */
    @Override
    public String toString() {
        return id.toString();
    }
}