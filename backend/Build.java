import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;

public class Build {
  public static void main(String[] args) throws Exception {
    Files.createDirectories(Path.of("out"));
    List<String> options =
        new ArrayList<>(List.of("--release", "17", "--add-modules", "jdk.httpserver", "-d", "out"));
    for (String dir : List.of("src", "test"))
      try (var files = Files.walk(Path.of(dir))) {
        files.filter(p -> p.toString().endsWith(".java")).forEach(p -> options.add(p.toString()));
      }
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("Install JDK 17 or later");
    if (compiler.run(null, null, null, options.toArray(String[]::new)) != 0)
      throw new IllegalStateException("Compilation failed");
  }
}
