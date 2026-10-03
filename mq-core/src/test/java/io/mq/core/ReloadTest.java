package io.mq.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.mq.core.reload.FolderWatcher;
import io.mq.core.reload.ResourceSet;
import io.mq.core.reload.ResourceStore;

class ReloadTest {

    private static String xml(String desc) {
        return "<Resource xmlns=\"http://xml.metamug.net/resource/1.0\" v=\"1.0\"><Desc>" + desc + "</Desc>"
                + "<Request method=\"GET\"><Text id=\"t\">x</Text></Request></Resource>";
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name + ".xml"), content);
    }

    private static void await(BooleanSupplier c) throws InterruptedException {
        for (int i = 0; i < 500 && !c.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(c.getAsBoolean(), "condition not reached in 5 s");
    }

    @Test
    void invalidFileKeepsTheOldModelAndRecoversAfterTheFix(@TempDir Path dir) throws IOException {
        ResourceStore store = new ResourceStore();
        write(dir, "a", xml("one"));
        ResourceSet s1 = store.reload(dir);
        assertEquals("one", s1.get("a").desc());
        assertTrue(s1.problems().isEmpty());

        write(dir, "a", "<Resource><Desc>broken");
        ResourceSet s2 = store.reload(dir);
        assertEquals("one", s2.get("a").desc(), "the old model is still served");
        assertEquals(1, s2.problems().get("a.xml").size());
        assertTrue(s2.problemSummary().contains("a.xml:"));

        write(dir, "a", xml("two"));
        ResourceSet s3 = store.reload(dir);
        assertEquals("two", s3.get("a").desc());
        assertTrue(s3.problems().isEmpty());
        assertEquals(3, s3.generation());
    }

    @Test
    void newInvalidFileHasNoModelAndDeletedFileDisappears(@TempDir Path dir) throws IOException {
        ResourceStore store = new ResourceStore();
        write(dir, "bad", "not xml");
        write(dir, "good", xml("g"));
        ResourceSet s = store.reload(dir);
        assertNull(s.get("bad"));
        assertNotNull(s.get("good"));
        assertFalse(s.problems().isEmpty());

        Files.delete(dir.resolve("good.xml"));
        assertNull(store.reload(dir).get("good"));
    }

    @Test
    void aSnapshotIsNeverChangedByALaterReload(@TempDir Path dir) throws IOException {
        ResourceStore store = new ResourceStore();
        write(dir, "a", xml("one"));
        ResourceSet before = store.reload(dir);
        write(dir, "b", xml("two"));
        store.reload(dir);
        assertEquals(1, before.resources().size());
        assertEquals(2, store.current().resources().size());
    }

    @Test
    void watcherAppliesABurstOfTwentyFiles(@TempDir Path dir) throws Exception {
        ResourceStore store = new ResourceStore();
        store.reload(dir);
        try (FolderWatcher w = FolderWatcher.start(dir, () -> store.reload(dir), 15)) {
            for (int i = 1; i <= 20; i++) {
                write(dir, "f" + i, xml("v" + i));
            }
            await(() -> store.current().resources().size() == 20);
            for (int i = 1; i <= 20; i++) {
                Files.delete(dir.resolve("f" + i + ".xml"));
            }
            await(() -> store.current().resources().isEmpty());
        }
    }
}
