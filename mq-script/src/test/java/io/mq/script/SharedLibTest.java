package io.mq.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Option 2: scripts share ordinary Kotlin code from a lib folder; a change to the lib reaches every script without a restart. */
class SharedLibTest {

    private static void write(Path file, String text, long bump) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + bump));
    }

    private static Object run(DevScriptLoader l, String script) throws Exception {
        Response r = new Response();
        l.run(script, new Params(Map.of()), new Steps(Map.of()), r, new RequestInfo("7", null, null, "GET"));
        return r.get("v");
    }

    @Test
    void twoScriptsShareLibCodeAndALibChangeReachesBoth(@TempDir Path dir) throws Exception {
        write(dir.resolve("lib/util/Greet.kt"), "package util\nobject Greet { fun hi(n: String) = \"hello \" + n }\n", 0);
        write(dir.resolve("scripts/a.kts"), "import util.Greet\nresponse[\"v\"] = Greet.hi(\"a\")\n", 0);
        write(dir.resolve("scripts/b.kts"), "import util.Greet\nresponse[\"v\"] = Greet.hi(\"b\") + request.id\n", 0);
        DevScriptLoader l = new DevScriptLoader(dir.resolve("scripts"), null, dir.resolve("lib"));
        assertEquals("hello a", run(l, "a"));
        assertEquals("hello b7", run(l, "b"));

        write(dir.resolve("lib/util/Greet.kt"), "package util\nobject Greet { fun hi(n: String) = \"HELLO \" + n }\n", 5000);
        assertEquals("HELLO a", run(l, "a"), "script a was compiled again against the changed lib");
        assertEquals("HELLO b7", run(l, "b"), "and so was script b");

        write(dir.resolve("lib/util/Extra.kt"), "package util\nfun shout(s: String) = s.uppercase() + \"!\"\n", 10000);
        write(dir.resolve("scripts/c.kts"), "import util.shout\nresponse[\"v\"] = shout(\"new\")\n", 10000);
        assertEquals("NEW!", run(l, "c"), "a new lib file and a new script that uses it");
    }

    @Test
    void aLibThatDoesNotCompileIsReportedWithFileAndLine(@TempDir Path dir) throws Exception {
        write(dir.resolve("lib/Bad.kt"), "package bad\nobject Bad { fun f() = missing() }\n", 0);
        write(dir.resolve("scripts/a.kts"), "response[\"v\"] = 1\n", 0);
        DevScriptLoader l = new DevScriptLoader(dir.resolve("scripts"), null, dir.resolve("lib"));
        RuntimeException e = assertThrows(RuntimeException.class, () -> run(l, "a"));
        assertTrue(e.getMessage().contains("Bad.kt") && e.getMessage().contains(":2:"), e.getMessage());

        write(dir.resolve("lib/Bad.kt"), "package bad\nobject Bad { fun f() = 1 }\n", 5000);
        assertEquals(1L, ((Number) run(l, "a")).longValue(), "fixed lib, the script runs again");
    }

    @Test
    void withoutALibFolderScriptsStillRun(@TempDir Path dir) throws Exception {
        write(dir.resolve("scripts/a.kts"), "response[\"v\"] = 5\n", 0);
        DevScriptLoader l = new DevScriptLoader(dir.resolve("scripts"), null, dir.resolve("no-lib-here"));
        assertEquals(5L, ((Number) run(l, "a")).longValue());
    }
}
