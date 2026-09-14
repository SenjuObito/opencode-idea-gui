package com.opencodebuddy.i18n;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.Assert.*;

public class OpenCodeBuddyBundleI18nTest {

    private static final String[] LOCALE_SUFFIXES = {
        "",         // default (English)
        "_en",      // English
        "_zh",      // Simplified Chinese
        "_zh_TW",   // Traditional Chinese
        "_es",      // Spanish
        "_fr",      // French
        "_ja",      // Japanese
        "_ru",      // Russian
        "_hi"       // Hindi
    };

    private static final String[] CRITICAL_NOTIFICATION_KEYS = {
        "notifier.thinking",
        "notifier.generating",
        "notifier.waiting",
        "notifier.askUserQuestion.title",
        "notifier.askUserQuestion.message",
        "status.widgetName",
        "status.defaultTooltip"
    };

    private Properties loadBundleProperties(String suffix) throws IOException {
        String resourcePath = "/messages/OpenCodeBuddyBundle" + suffix + ".properties";
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull("Resource bundle file not found: " + resourcePath, in);
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props;
        }
    }

    @Test
    public void testAllResourceBundlesExistAndLoadable() throws IOException {
        for (String suffix : LOCALE_SUFFIXES) {
            Properties props = loadBundleProperties(suffix);
            assertFalse("Bundle " + suffix + " should not be empty", props.isEmpty());
        }
    }

    @Test
    public void testNotificationKeysExistInAllBundles() throws IOException {
        for (String suffix : LOCALE_SUFFIXES) {
            Properties props = loadBundleProperties(suffix);
            for (String key : CRITICAL_NOTIFICATION_KEYS) {
                String val = props.getProperty(key);
                assertNotNull("Bundle " + suffix + " missing key: " + key, val);
                assertFalse("Bundle " + suffix + " key " + key + " should not be blank", val.trim().isEmpty());
            }
        }
    }

    @Test
    public void testNotificationTextsStrictlyUseOpenCodeBuddyBranding() throws IOException {
        for (String suffix : LOCALE_SUFFIXES) {
            Properties props = loadBundleProperties(suffix);
            for (String key : CRITICAL_NOTIFICATION_KEYS) {
                String val = props.getProperty(key);
                assertNotNull(val);
                assertFalse("Bundle " + suffix + " key " + key + " ('" + val + "') should NOT contain 'Claude'",
                        val.toLowerCase(Locale.ROOT).contains("claude"));
                assertTrue("Bundle " + suffix + " key " + key + " ('" + val + "') should contain 'OpenCode Buddy'",
                        val.contains("OpenCode Buddy"));
            }
        }
    }

    @Test
    public void testActionAndTemplateBranding() throws IOException {
        for (String suffix : LOCALE_SUFFIXES) {
            Properties props = loadBundleProperties(suffix);
            String sendToGui = props.getProperty("action.sendToGui.text");
            if (sendToGui != null) {
                assertFalse("action.sendToGui.text in bundle " + suffix + " should NOT contain 'Claude'",
                        sendToGui.toLowerCase(Locale.ROOT).contains("claude"));
            }
        }
    }
}
