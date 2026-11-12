import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

public class PackageBackend {
  public static void main(String[] args) throws Exception {
    Manifest m = new Manifest();
    m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    m.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "rescuelink.Server");
    Path jar = Path.of("../deliverables/RescueLink-backend.jar");
    Files.createDirectories(jar.getParent());
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), m);
        var files = Files.walk(Path.of("out/rescuelink"))) {
      for (Path p :
          files
              .filter(
                  f ->
                      f.toString().endsWith(".class")
                          && !f.getFileName().toString().startsWith("IntegrationTest"))
              .toList()) {
        out.putNextEntry(new JarEntry(Path.of("out").relativize(p).toString().replace('\\', '/')));
        Files.copy(p, out);
        out.closeEntry();
      }
    }
    System.out.println(jar.toAbsolutePath());
  }
}
