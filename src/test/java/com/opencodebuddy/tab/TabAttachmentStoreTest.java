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
 * The attachments one tab staged.
 *
 * <p>An attachment belongs to the tab that picked it: it is written into that
 * tab's own directory, another tab cannot see it, and it disappears with the
 * tab.</p>
 */
public class TabAttachmentStoreTest {

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void stagesAnAttachmentForItsTab() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root.resolve("tab-a"));
        try {
            TabAttachmentStore.Staged staged = store.stage(
                    "att-1", "note.txt", "text/plain", base64("hello"));

            assertNotNull(staged);
            assertEquals("att-1", staged.attachmentId());
            assertEquals(5, staged.size());
            assertTrue(Files.exists(staged.path()));
            assertEquals(base64("hello"), store.readData("att-1"));
        } finally {
            store.release();
        }
    }

    @Test
    public void tabsDoNotSeeEachOthersAttachments() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore storeA = new TabAttachmentStore(root.resolve("tab-a"));
        TabAttachmentStore storeB = new TabAttachmentStore(root.resolve("tab-b"));
        try {
            storeA.stage("att-1", "note.txt", "text/plain", base64("A"));
            storeB.stage("att-1", "note.txt", "text/plain", base64("B"));

            assertEquals(base64("A"), storeA.readData("att-1"));
            assertEquals(base64("B"), storeB.readData("att-1"));
            assertNull(storeA.get("att-b-only"));
            assertEquals(1, storeA.size());
            assertEquals(1, storeB.size());
        } finally {
            storeA.release();
            storeB.release();
        }
    }

    @Test
    public void closingATabReleasesOnlyItsAttachments() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore storeA = new TabAttachmentStore(root.resolve("tab-a"));
        TabAttachmentStore storeB = new TabAttachmentStore(root.resolve("tab-b"));
        try {
            storeA.stage("att-1", "a.txt", "text/plain", base64("A"));
            storeB.stage("att-1", "b.txt", "text/plain", base64("B"));

            assertEquals(1, storeA.release());

            assertNull(storeA.get("att-1"));
            assertNull(storeA.readData("att-1"));
            assertNotNull(storeB.get("att-1"));
            assertFalse(Files.exists(storeA.directory()));
            assertTrue(Files.exists(storeB.directory()));
        } finally {
            storeA.release();
            storeB.release();
        }
    }

    @Test
    public void refusesWhatItCannotStore() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root.resolve("tab-a"));
        try {
            assertNull(store.stage("att-1", "note.txt", "text/plain", "not base64!"));
            assertNull(store.stage("att-1", "note.txt", "text/plain", ""));
            assertNull(store.stage(null, "note.txt", "text/plain", base64("x")));
            assertEquals(0, store.size());
        } finally {
            store.release();
        }
    }

    @Test
    public void twoTabsCanHoldTheSameFileName() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore storeA = new TabAttachmentStore(root.resolve("tab-a"));
        TabAttachmentStore storeB = new TabAttachmentStore(root.resolve("tab-b"));
        try {
            storeA.stage("att-1", "note.txt", "text/plain", base64("A"));
            storeB.stage("att-1", "note.txt", "text/plain", base64("B"));

            assertFalse(storeA.get("att-1").path().equals(storeB.get("att-1").path()));
        } finally {
            storeA.release();
            storeB.release();
        }
    }
}
