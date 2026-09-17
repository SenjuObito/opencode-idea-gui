package com.opencodebuddy.handler;

import com.opencodebuddy.handler.core.HandlerContext;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class TabHandlerTest {

    @Test
    public void testSupportedTypes() {
        TabHandler handler = new TabHandler(null);
        String[] types = handler.getSupportedTypes();
        assertNotNull(types);
        assertTrue(java.util.Arrays.asList(types).contains("create_new_tab"));
        assertTrue(java.util.Arrays.asList(types).contains("update_tab_title"));
    }

    @Test
    public void testUpdateTabTitlePlainString() {
        AtomicReference<String> updatedTitle = new AtomicReference<>();
        TabHandler handler = new TabHandler(null, updatedTitle::set);

        boolean handled = handler.handle("update_tab_title", "Refactor Auth Module");
        assertTrue(handled);
        assertEquals("Refactor Auth Module", updatedTitle.get());
    }

    @Test
    public void testUpdateTabTitleJsonPayload() {
        AtomicReference<String> updatedTitle = new AtomicReference<>();
        TabHandler handler = new TabHandler(null, updatedTitle::set);

        boolean handled = handler.handle("update_tab_title", "{\"title\":\"Fix Database Timeout\"}");
        assertTrue(handled);
        assertEquals("Fix Database Timeout", updatedTitle.get());
    }

    @Test
    public void testUnhandledTypeReturnsFalse() {
        TabHandler handler = new TabHandler(null);
        boolean handled = handler.handle("some_random_type", "data");
        assertFalse(handled);
    }
}
