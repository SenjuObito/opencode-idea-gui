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
    public void aChunkedUploadIsOnlyReadableWhenItIsComplete() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root.resolve("tab-a"));
        try {
            // "hello world" is 11 bytes; the webview announces that up front.
            TabAttachmentStore.Staged prepared = store.prepare("att-2", "note.txt", "text/plain", 11);
            assertNotNull(prepared);
            assertEquals(0, prepared.size());
            assertFalse(prepared.ready());
            // A half-uploaded file must not be sendable as if it were whole.
            assertNull(store.readData("att-2"));

            assertNotNull(store.append("att-2", 0, base64("hello ")));
            TabAttachmentStore.Staged afterSecond = store.append("att-2", 6, base64("world"));
            assertNotNull(afterSecond);
            assertEquals(11, afterSecond.size());

            TabAttachmentStore.Staged done = store.complete("att-2");
            assertNotNull(done);
            assertTrue(done.ready());
            assertEquals(base64("hello world"), store.readData("att-2"));
        } finally {
            store.release();
        }
    }

    @Test
    public void aChunkOutOfOrderIsRefusedInsteadOfCorruptingTheFile() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root.resolve("tab-a"));
        try {
            store.prepare("att-3", "note.txt", "text/plain", 10);
            store.append("att-3", 0, base64("01234"));
            // The bridge delivers in order; a gap means a lost frame.
            assertNull(store.append("att-3", 7, base64("56789")));
        } finally {
            store.release();
        }
    }

    @Test
    public void anUploadThatNeverReachesItsAnnouncedSizeIsNotCompletable() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root.resolve("tab-a"));
        try {
            store.prepare("att-4", "note.txt", "text/plain", 20);
            store.append("att-4", 0, base64("short"));
            assertNull(store.complete("att-4"));
            assertNull(store.readData("att-4"));
        } finally {
            store.release();
        }
    }

    @Test
    public void anAbortedUploadLeavesNoFileBehind() throws Exception {
        Path root = Files.createTempDirectory("attachments-test");
        TabAttachmentStore store = new TabAttachmentStore(root.resolve("tab-a"));
        try {
            TabAttachmentStore.Staged staged = store.prepare("att-5", "note.txt", "text/plain", 5);
            assertNotNull(staged);
            Path file = staged.path();
            store.abort("att-5");
            assertFalse(Files.exists(file));
            assertNull(store.get("att-5"));
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
