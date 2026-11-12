package rescuelink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** End-to-end TCP framing and login smoke test; run after compilation. */
public final class TcpSmokeTest {
  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("rescuelink-tcp-smoke-");
    try (Server business = new Server(dir, "127.0.0.1", 0, "TestPassword123!")) {
      business.start();
      try (TcpSocketServer gateway = new TcpSocketServer("127.0.0.1", 0, business.port())) {
        gateway.start();
        Properties request = Server.props("op", "login", "username", "admin", "password", "TestPassword123!");
        StringWriter writer = new StringWriter();
        request.store(writer, null);
        byte[] payload = writer.toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", gateway.port())) {
          socket.setSoTimeout(10000);
          DataOutputStream out = new DataOutputStream(socket.getOutputStream());
          DataInputStream in = new DataInputStream(socket.getInputStream());
          out.writeInt(payload.length);
          out.write(payload);
          out.flush();
          int status = in.readInt();
          int length = in.readInt();
          if (length < 0 || length > 900000) throw new AssertionError("Invalid response length");
          byte[] response = new byte[length];
          in.readFully(response);
          Properties result = new Properties();
          result.load(new ByteArrayInputStream(response));
          if (status != 200 || result.getProperty("token", "").isEmpty())
            throw new AssertionError("TCP login failed: " + status + " " + result);
          System.out.println("PASS: TCP login and response framing");
        }
      }
    }
  }
}
