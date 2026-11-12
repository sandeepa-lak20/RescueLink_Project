package lk.rescuelink;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Length-framed TCP client. Protocol: request length + UTF-8 Properties text;
 * response status + response length + ISO-8859-1 Properties bytes. */
final class Api {
  static class Failure extends IOException {
    final int code;
    Failure(int c, String m) { super(m); code = c; }
  }

  static Properties fields(String... a) {
    Properties p = new Properties();
    for (int n = 0; n < a.length; n += 2) p.setProperty(a[n], a[n + 1]);
    return p;
  }

  static String serialize(Properties p) throws IOException {
    StringWriter w = new StringWriter();
    p.store(w, null);
    return w.toString();
  }

  static Properties parse(String s) throws IOException {
    Properties p = new Properties();
    p.load(new StringReader(s));
    return p;
  }

  static Properties call(String address, Properties p) throws IOException {
    String host = address;
    int port = 9090;
    int colon = address.lastIndexOf(':');
    if (colon > 0 && colon < address.length() - 1) {
      host = address.substring(0, colon).trim();
      try { port = Integer.parseInt(address.substring(colon + 1)); }
      catch (NumberFormatException e) { throw new IOException("Port must be a number, e.g. 192.168.1.10:9090"); }
    }
    if (host.isEmpty() || port < 1 || port > 65535) throw new IOException("Enter server IP:port, e.g. 192.168.1.10:9090");
    byte[] request = serialize(p).getBytes(StandardCharsets.UTF_8);
    if (request.length > 800000) throw new IOException("Request is too large");
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 10000);
      socket.setSoTimeout(35000);
      DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
      DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
      out.writeInt(request.length);
      out.write(request);
      out.flush();
      int code = in.readInt();
      int length = in.readInt();
      if (length < 0 || length > 900000) throw new IOException("Invalid response length from server");
      byte[] response = new byte[length];
      in.readFully(response);
      Properties result = new Properties();
      result.load(new ByteArrayInputStream(response));
      if (code != 200) throw new Failure(code, result.getProperty("error", "Server error " + code));
      return result;
    } catch (SocketTimeoutException e) {
      throw new IOException("Connection timed out. Check server IP, port, Wi-Fi and firewall.", e);
    }
  }
}
