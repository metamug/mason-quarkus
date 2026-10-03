package io.mq.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shared scripting host: a backend without the compiler gets compiled scripts (and the lib) from one host process. */
class SharedHostTest {

    static String hostUrl;

    @BeforeAll
    static void startHost() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ScriptHost.main(new String[] { String.valueOf(port) });
        hostUrl = "http://127.0.0.1:" + port;
    }

    private static void write(Path file, String text, long bump) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + bump));
    }

    private static Object run(RemoteScriptLoader l, String script) throws Exception {
        Response r = new Response();
        l.run(script, new Params(Map.of("n", "3")), new Steps(Map.of()), r, new RequestInfo("7", null, null, "GET"));
        return r.get("v");
    }

    private static List<String> classpath() {
        return Arrays.asList(System.getProperty("java.class.path").split(java.io.File.pathSeparator));
    }

    @Test
    void aBackendWithoutTheCompilerRunsScriptsAndLibCompiledByTheHost(@TempDir Path dir) throws Exception {
        write(dir.resolve("lib/util/Greet.kt"), "package util\nobject Greet { fun hi(n: String) = \"hello \" + n }\n", 0);
        write(dir.resolve("scripts/a.kts"), "import util.Greet\nresponse[\"v\"] = Greet.hi(params[\"n\"] ?: \"?\") + request.id\n", 0);
        RemoteScriptLoader l = new RemoteScriptLoader(hostUrl, dir.resolve("scripts"), dir.resolve("lib"), classpath());
        assertEquals("hello 37", run(l, "a"));
        assertEquals("hello 37", run(l, "a"), "second call: from the cache, no new compile");

        write(dir.resolve("lib/util/Greet.kt"), "package util\nobject Greet { fun hi(n: String) = \"HELLO \" + n }\n", 5000);
        assertEquals("HELLO 37", run(l, "a"), "a lib change reaches the script on the backend");

        write(dir.resolve("scripts/a.kts"), "response[\"v\"] = oops(\n", 10000);
        RuntimeException e = assertThrows(RuntimeException.class, () -> run(l, "a"));
        assertTrue(e.getMessage().contains("a.kts:"), e.getMessage());
    }
}
