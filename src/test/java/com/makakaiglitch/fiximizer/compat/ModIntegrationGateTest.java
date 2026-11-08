package com.makakaiglitch.fiximizer.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModIntegrationGateTest {
    @Test
    void integrationCanOnlyActivateWhenTargetModIsInstalledAndEnabled() {
        assertTrue(ModIntegrationManager.shouldActivate(true, true));
        assertFalse(ModIntegrationManager.shouldActivate(false, true));
        assertFalse(ModIntegrationManager.shouldActivate(true, false));
        assertFalse(ModIntegrationManager.shouldActivate(false, false));
    }
}
