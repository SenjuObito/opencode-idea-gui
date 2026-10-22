package com.opencodebuddy.tab;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The attachments a tab staged.
 *
 * <p>An attachment belongs to the tab that picked it: it is written into that
 * tab's own directory, it is not readable by another tab, and it disappears
 * with the tab.</p>
 */
public class TabAttachmentStoreTest {

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void stagesAnAttachmentForItsTab() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root);
        try {
            TabAttachmentStore.Staged staged = store.stage(
                    "tab-a", "att-1", "note.txt", "text/plain", base64("hello"));

            assertNotNull(staged);
            assertEquals("att-1", staged.attachmentId());
            assertEquals(5, staged.size());
            assertTrue(Files.exists(staged.path()));
            assertEquals(base64("hello"), store.readData("tab-a", "att-1"));
        } finally {
            store.clear();
        }
    }

    @Test
    public void tabsDoNotSeeEachOthersAttachments() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root);
        try {
            store.stage("tab-a", "att-1", "note.txt", "text/plain", base64("A"));
            store.stage("tab-b", "att-1", "note.txt", "text/plain", base64("B"));

            assertEquals(base64("A"), store.readData("tab-a", "att-1"));
            assertEquals(base64("B"), store.readData("tab-b", "att-1"));
            assertEquals(2, store.size());
        } finally {
            store.clear();
        }
    }

    @Test
    public void closingATabReleasesOnlyItsAttachments() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root);
        try {
            store.stage("tab-a", "att-1", "a.txt", "text/plain", base64("A"));
            store.stage("tab-b", "att-1", "b.txt", "text/plain", base64("B"));

            assertEquals(1, store.releaseTab("tab-a"));

            assertNull(store.get("tab-a", "att-1"));
            assertNull(store.readData("tab-a", "att-1"));
            assertNotNull(store.get("tab-b", "att-1"));
            assertFalse(Files.exists(store.tabDirectory("tab-a")));
            assertTrue(Files.exists(store.tabDirectory("tab-b")));
        } finally {
            store.clear();
        }
    }

    @Test
    public void refusesWhatItCannotStore() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root);
        try {
            assertNull(store.stage("tab-a", "att-1", "note.txt", "text/plain", "not base64!"));
            assertNull(store.stage("tab-a", "att-1", "note.txt", "text/plain", ""));
            assertNull(store.stage(null, "att-1", "note.txt", "text/plain", base64("x")));
            assertEquals(0, store.size());
        } finally {
            store.clear();
        }
    }

    @Test
    public void doesNotLetOneTabOverwriteAnotherTabsFile() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root);
        try {
            store.stage("tab-a", "att-1", "note.txt", "text/plain", base64("A"));
            store.stage("tab-b", "att-1", "note.txt", "text/plain", base64("B"));

            assertFalse(store.get("tab-a", "att-1").path().equals(store.get("tab-b", "att-1").path()));
        } finally {
            store.clear();
        }
    }
}
