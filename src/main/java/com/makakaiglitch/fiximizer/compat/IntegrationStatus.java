package com.makakaiglitch.fiximizer.compat;

public record IntegrationStatus(String displayName, String detectedModId) {
    public boolean detected() {
        return detectedModId != null;
    }
}
