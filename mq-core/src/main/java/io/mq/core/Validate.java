package io.mq.core;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.mq.core.parser.ParseResult;
import io.mq.core.parser.ResourceParser;

/**
 * Validates resource XML files and prints one line per file, sorted as strings:
 * {@code name<TAB>VALID} or {@code name<TAB>INVALID<TAB>problems<TAB>line<TAB>message} (the first problem).
 * Arguments are files or folders (every *.xml in them). The output is identical on the JVM and in a native binary.
 * Exit code 0 when every file is valid, 1 when not, 2 on a usage or I/O error.
 */
public final class Validate {

    private Validate() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: Validate <file-or-folder>...");
            System.exit(2);
        }
        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.getFileName().toString().endsWith(".xml")).forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        PrintStream out = new PrintStream(System.out, false, "UTF-8");
        boolean allValid = true;
        List<String> lines = new ArrayList<>();
        for (Path f : files) {
            ParseResult r = ResourceParser.parse(f);
            lines.add(line(r));
            allValid &= r.valid();
        }
        lines.sort(null);
        lines.forEach(out::println);
        out.flush();
        System.exit(allValid ? 0 : 1);
    }

    /** the one-line verdict of a result, as printed by main and compared with golden/expected.tsv */
    public static String line(ParseResult r) {
        String name = r.file().endsWith(".xml") ? r.file().substring(0, r.file().length() - 4) : r.file();
        if (r.valid()) {
            return name + "\tVALID";
        }
        var p = r.problems().get(0);
        return name + "\tINVALID\t" + r.problems().size() + "\t" + p.line() + "\t" + p.message().replace('\t', ' ').replace('\n', ' ');
    }
}
