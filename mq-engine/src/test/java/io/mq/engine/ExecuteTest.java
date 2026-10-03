package io.mq.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.mq.core.reload.ResourceStore;
import io.mq.plugin.Plugin;
import io.mq.plugin.PluginDirectory;
import io.mq.plugin.PluginLoader;
import io.mq.plugin.PluginRequest;

/** Execute: plugin classes found by name among the declared ones, Arg values and paths, results usable by later steps, redeploy of a jar. */
class ExecuteTest {

    public static final class Greeter implements Plugin {
        @Override
        public Object process(PluginRequest request, Map<String, Object> args) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("greeting", "Hello " + args.get("who") + " from " + args.get("fixed"));
            out.put("pathType", args.get("count") == null ? "null" : args.get("count").getClass().getSimpleName());
            out.put("param", request.params().get("name"));
            out.put("id", request.id());
            return out;
        }
    }

    private Dispatcher mq(Path dir, PluginLoader plugins, String xml) throws IOException {
        Files.writeString(dir.resolve("api.xml"), "<Resource xmlns=\"http://xml.metamug.net/resource/1.0\" v=\"1.0\">\n" + xml + "\n</Resource>");
        ResourceStore store = new ResourceStore();
        var set = store.reload(dir);
        assertTrue(set.problems().isEmpty(), set.problemSummary());
        return new Dispatcher(store::current, new Engine(n -> null, null, null, new ExecuteHandler(() -> plugins, n -> null)));
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(Reply r) {
        return (Map<String, Object>) r.body();
    }

    @Test
    void argsAreTextWithVariablesOrTheValueAnMpathFinds(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, () -> Map.of(Greeter.class.getName(), new Greeter()), """
                <Request method="GET" item="true">
                  <Text id="q" output="false">ignored</Text>
                  <Execute id="run" classname="io.mq.engine.ExecuteTest$Greeter" output="true">
                    <Arg name="who" value="$name"/>
                    <Arg name="fixed" value="the shop"/>
                    <Arg name="count" path="$n"/>
                  </Execute>
                  <Text id="again">$[run].greeting / $[run].param / $[run].id</Text>
                </Request>""");
        Reply r = d.handle("GET", "/v1.0/api/42", "name=Ada&n=3", null, null);
        assertEquals(200, r.status(), r.json());
        assertEquals("Hello Ada from the shop", ((Map<?, ?>) body(r).get("run")).get("greeting"));
        assertEquals("Hello Ada from the shop / Ada / 42", body(r).get("again"), "a later step reads the plugin result by mpath");
    }

    public static final class Sloppy implements Plugin {
        @Override
        public Object process(PluginRequest request, Map<String, Object> args) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fine", 1);
            out.put("when", java.time.LocalDate.of(2026, 1, 2));
            return out;
        }
    }

    @Test
    void aResultThatIsNotJsonReadyIsAnErrorNotAToString(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, () -> Map.of(Sloppy.class.getName(), new Sloppy()), """
                <Request method="GET">
                  <Execute id="run" classname="io.mq.engine.ExecuteTest$Sloppy" output="true"/>
                </Request>""");
        Reply r = d.handle("GET", "/v1.0/api", null, null, null);
        assertEquals(500, r.status());
        String detail = d.engine().errorDetail(String.valueOf(body(r).get("errorId")));
        assertTrue(detail.contains("not JSON-ready") && detail.contains("LocalDate") && detail.contains("result.when"), detail);
    }

    @Test
    void anUndeclaredClassIsAnErrorThatNamesIt(@TempDir Path dir) throws IOException {
        Dispatcher d = mq(dir, () -> Map.of(), """
                <Request method="GET">
                  <Execute id="run" classname="com.example.Missing" output="true"/>
                </Request>""");
        Reply r = d.handle("GET", "/v1.0/api", null, null, null);
        assertEquals(500, r.status());
        assertTrue(d.engine().errorDetail(String.valueOf(body(r).get("errorId"))).contains("com.example.Missing"));
    }

    // ---------------------------------------------------------------- redeploy of a plugin jar

    private static void jar(Path jar, Path src, String className, String returns) throws IOException {
        Path build = Files.createTempDirectory("plugin");
        Path java = build.resolve(className + ".java");
        Files.writeString(java, "public class " + className + " implements io.mq.plugin.Plugin {\n"
                + "  public Object process(io.mq.plugin.PluginRequest r, java.util.Map<String, Object> a) { return \"" + returns + "\"; }\n}\n");
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        int rc = jc.run(null, null, null, "-cp", System.getProperty("java.class.path"), "-d", build.toString(), java.toString());
        assertEquals(0, rc, "the plugin compiles");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry(className + ".class"));
            j.write(Files.readAllBytes(build.resolve(className + ".class")));
            j.putNextEntry(new JarEntry("META-INF/services/io.mq.plugin.Plugin"));
            j.write((className + "\n").getBytes());
        }
    }

    @Test
    void aChangedJarIsPickedUpWithoutRestart(@TempDir Path dir) throws IOException {
        Path plugins = Files.createDirectory(dir.resolve("plugins"));
        Path jar = plugins.resolve("hello.jar");
        jar(jar, dir, "Hello", "version one");
        PluginDirectory loader = new PluginDirectory(plugins, getClass().getClassLoader());
        Dispatcher d = mq(dir, loader, """
                <Request method="GET">
                  <Execute id="run" classname="Hello" output="true"/>
                </Request>""");
        assertEquals("version one", body(d.handle("GET", "/v1.0/api", null, null, null)).get("run"));

        jar(jar, dir, "Hello", "version two");
        Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals("version two", body(d.handle("GET", "/v1.0/api", null, null, null)).get("run"), "the redeployed plugin answers");

        Files.delete(jar);
        Reply gone = d.handle("GET", "/v1.0/api", null, null, null);
        assertEquals(500, gone.status(), "a removed plugin is reported, not served from memory");
    }
}
