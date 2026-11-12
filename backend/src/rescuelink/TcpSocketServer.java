package rescuelink;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** TCP socket front end for the existing application service. Mobile clients use Socket;
 * this gateway forwards each request to the business-rule service on loopback only.
 * Frame: request int length + UTF-8 Properties text; response int status + int length + Properties bytes. */
public final class TcpSocketServer implements AutoCloseable {
  private final ServerSocket serverSocket;
  private final int internalHttpPort;
  private final ExecutorService clients = Executors.newCachedThreadPool();
  private volatile boolean running = true;

  public TcpSocketServer(String bindHost, int tcpPort, int internalHttpPort) throws IOException {
    this.internalHttpPort = internalHttpPort;
    serverSocket = new ServerSocket();
    serverSocket.setReuseAddress(true);
    serverSocket.bind(new InetSocketAddress(bindHost, tcpPort));
  }

  public int port() { return serverSocket.getLocalPort(); }

  public void start() {
    Thread acceptor = new Thread(() -> {
      while (running) {
        try {
          Socket client = serverSocket.accept();
          clients.execute(() -> handle(client));
        } catch (SocketException e) {
          if (running) System.err.println("TCP accept failed: " + e.getMessage());
        } catch (IOException e) {
          if (running) System.err.println("TCP accept failed: " + e.getMessage());
        }
      }
    }, "rescuelink-tcp-acceptor");
    acceptor.setDaemon(false);
    acceptor.start();
  }

  private void handle(Socket client) {
    try (Socket s = client) {
      s.setSoTimeout(35000);
      DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
      int length = in.readInt();
      if (length < 0 || length > 800000) { writeResponse(out, 413, Server.props("error", "Request too large")); return; }
      byte[] bytes = new byte[length];
      in.readFully(bytes);
      Properties fields = new Properties();
      fields.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
      StringBuilder form = new StringBuilder();
      for (String key : fields.stringPropertyNames()) {
        if (form.length() > 0) form.append('&');
        form.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
        form.append('=');
        form.append(URLEncoder.encode(fields.getProperty(key), StandardCharsets.UTF_8));
      }
      byte[] body = form.toString().getBytes(StandardCharsets.UTF_8);
      HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + internalHttpPort + "/api").openConnection();
      c.setConnectTimeout(5000); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
      c.setFixedLengthStreamingMode(body.length);
      try (OutputStream request = c.getOutputStream()) { request.write(body); }
      int status = c.getResponseCode();
      InputStream response = status < 400 ? c.getInputStream() : c.getErrorStream();
      byte[] result;
      if (response == null) result = new byte[0];
      else try (InputStream r = response; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) { r.transferTo(buffer); result = buffer.toByteArray(); }
      c.disconnect();
      out.writeInt(status); out.writeInt(result.length); out.write(result); out.flush();
    } catch (EOFException e) {
      // Client disconnected before sending a complete frame.
    } catch (Exception e) {
      System.err.println("TCP request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      try {
        // A response is not always possible if the connection itself is broken.
      } catch (Exception ignored) { }
    }
  }

  private static void writeResponse(DataOutputStream out, int status, Properties p) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    p.store(buffer, null);
    byte[] b = buffer.toByteArray();
    out.writeInt(status); out.writeInt(b.length); out.write(b); out.flush();
  }

  public void close() {
    running = false;
    try { serverSocket.close(); } catch (IOException ignored) { }
    clients.shutdownNow();
  }

  public static void main(String[] args) throws Exception {
    String password = System.getenv("RESCUELINK_ADMIN_PASSWORD");
    File data = new File(args.length > 0 ? args[0] : "data");
    int tcpPort = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
    String bindHost = args.length > 2 ? args[2] : "0.0.0.0";
    Server business = new Server(data.toPath(), "127.0.0.1", 0, password);
    business.start();
    TcpSocketServer tcp = new TcpSocketServer(bindHost, tcpPort, business.port());
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { tcp.close(); business.close(); }));
    tcp.start();
    System.out.println("RescueLink TCP socket server listening on " + bindHost + ":" + tcp.port());
    System.out.println("Internal business service is loopback-only; do not expose it directly.");
  }
}
