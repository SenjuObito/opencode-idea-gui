package com.opencodebuddy.action.tab;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CreateNewTabActionTest {

    @Test
    public void actionInitializesWithProperties() {
        CreateNewTabAction action = new CreateNewTabAction();
        assertEquals(ActionUpdateThread.EDT, action.getActionUpdateThread());
        assertNotNull(action.getTemplatePresentation().getText());
        assertNotNull(action.getTemplatePresentation().getDescription());
    }
}
