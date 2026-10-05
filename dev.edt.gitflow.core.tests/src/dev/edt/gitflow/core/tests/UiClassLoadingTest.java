package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.osgi.framework.Bundle;

public class UiClassLoadingTest {
    @Test
    public void compareDependenciesAreDeclaredByUiBundle() {
        Bundle ui = Platform.getBundle("dev.edt.gitflow.ui");
        assertNotNull(ui);
        String dependencies = ui.getHeaders().get("Require-Bundle");
        assertTrue(dependencies.contains("org.eclipse.compare,"));
        assertTrue(dependencies.contains("org.eclipse.team.ui,"));
    }
}
