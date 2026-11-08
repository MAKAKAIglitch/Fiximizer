package com.makakaiglitch.fiximizer.compat;

public record IntegrationStatus(
        String displayName,
        String detectedModId,
        String version,
        boolean enabled
) {
    public boolean detected() {
        return detectedModId != null;
    }

    public boolean active() {
        return ModIntegrationManager.shouldActivate(detected(), enabled);
    }
}
