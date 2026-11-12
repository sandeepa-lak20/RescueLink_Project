package rescuelink;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Dependency-free Java 17 backend. HTTPS is terminated by the demo tunnel. */
public final class Server implements AutoCloseable {
  private final Properties state = new Properties();
  private final Path file;
  private final HttpServer http;
  private final ExecutorService workers = Executors.newFixedThreadPool(48);
  private final Semaphore polls = new Semaphore(24);
  private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private long revision;
  private volatile boolean closed;
  private static final Set<String> ROLES =
      Set.of("CITIZEN", "RESCUE", "HOSPITAL", "DISPATCHER", "ADMIN");
  private static final Set<String> TYPES =
      Set.of("AMBULANCE", "VEHICLE", "RESPONDER", "BED", "EQUIPMENT");

  public Server(Path folder, String host, int port, String adminPassword) throws Exception {
    Files.createDirectories(folder);
    file = folder.resolve("state.properties");
    if (Files.exists(file))
      try (InputStream in = Files.newInputStream(file)) {
        state.load(in);
      }
    revision = Long.parseLong(state.getProperty("revision", "0"));
    if (!state.containsKey("u.admin.role")) {
      passwordValid(adminPassword);
      createUser("admin", adminPassword, "ADMIN", true);
      save();
    }
    http = HttpServer.create(new InetSocketAddress(host, port), 32);
    http.setExecutor(workers);
    http.createContext(
        "/health",
        e -> {
          if (!e.getRequestMethod().equals("GET")) {
            respond(e, 405, props("error", "GET required"));
            return;
          }
          respond(e, 200, props("status", "ready"));
        });
    http.createContext("/api", this::handle);
  }

  public void start() {
    http.start();
  }

  public int port() {
    return http.getAddress().getPort();
  }

  public void close() {
    closed = true;
    synchronized (this) {
      notifyAll();
    }
    http.stop(0);
    workers.shutdownNow();
  }

  static Properties props(String... pairs) {
    Properties p = new Properties();
    for (int i = 0; i < pairs.length; i += 2) p.setProperty(pairs[i], pairs[i + 1]);
    return p;
  }

  private static void need(boolean ok, String message) {
    if (!ok) throw new Bad(400, message);
  }

  private static class Bad extends RuntimeException {
    final int code;

    Bad(int c, String m) {
      super(m);
      code = c;
    }
  }

  private static String required(Properties p, String k) {
    String v = p.getProperty(k, "").trim();
    need(!v.isEmpty(), "Missing " + k);
    return v;
  }

  private static String field(Properties p, String k, int max) {
    String v = required(p, k);
    need(v.length() <= max, "Too long: " + k);
    return v;
  }

  private static String userName(String value) {
    String u = value.toLowerCase(Locale.ROOT).trim();
    need(u.matches("[a-z0-9_]{3,24}"), "Username: 3–24 lowercase letters, digits or underscore");
    return u;
  }

  private static void passwordValid(String value) {
    need(
        value != null && value.length() >= 10 && value.length() <= 128,
        "Password must be 10–128 characters");
  }

  private String secret() {
    byte[] b = new byte[32];
    random.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String digest(String v) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hashPassword(String password, String salt) {
    PBEKeySpec spec =
        new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), 210000, 256);
    try {
      return Base64.getEncoder()
          .encodeToString(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                  .generateSecret(spec)
                  .getEncoded());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }

  private void createUser(String u, String password, String role, boolean approved) {
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    String s = Base64.getEncoder().encodeToString(salt);
    state.setProperty("u." + u + ".salt", s);
    state.setProperty("u." + u + ".password", hashPassword(password, s));
    state.setProperty("u." + u + ".role", role);
    state.setProperty("u." + u + ".approved", String.valueOf(approved));
  }

  private void save() throws IOException {
    Path temp = file.resolveSibling("state.tmp");
    try (OutputStream out = Files.newOutputStream(temp)) {
      state.store(out, "RescueLink persistent state; keep private");
    }
    try (FileChannel c = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      c.force(true);
    }
    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private synchronized String authenticate(Properties p) {
    String token = required(p, "token");
    String base = "s." + digest(token) + ".";
    String u = state.getProperty(base + "user");
    if (u == null
        || Long.parseLong(state.getProperty(base + "expires", "0")) < System.currentTimeMillis())
      throw new Bad(401, "Session expired; log in again");
    if (!Boolean.parseBoolean(state.getProperty("u." + u + ".approved")))
      throw new Bad(403, "Account awaiting administrator approval");
    return u;
  }

  private String role(String u) {
    return state.getProperty("u." + u + ".role");
  }

  private void roleRequired(String u, String... allowed) {
    if (!Arrays.asList(allowed).contains(role(u)))
      throw new Bad(403, "Your role cannot perform this action");
  }

  private Set<String> ids(String prefix, String suffix) {
    Set<String> ids = new TreeSet<>();
    for (String k : state.stringPropertyNames())
      if (k.startsWith(prefix) && k.endsWith(suffix))
        ids.add(k.substring(prefix.length(), k.length() - suffix.length()));
    return ids;
  }

  private boolean visible(String u, String id) {
    if (role(u).equals("ADMIN") || role(u).equals("DISPATCHER")) return true;
    if (u.equals(state.getProperty("i." + id + ".owner"))) return true;
    if (role(u).equals("HOSPITAL") && !"REPORTED".equals(state.getProperty("i." + id + ".status")))
      return true;
    for (String r : allocations(id))
      if (u.equals(state.getProperty("r." + r + ".owner"))) return true;
    return false;
  }

  private void incidentAccess(String u, String id) {
    need(state.containsKey("i." + id + ".owner"), "Unknown incident");
    if (!visible(u, id)) throw new Bad(403, "Incident access denied");
  }

  private Set<String> allocations(String id) {
    return ids("i." + id + ".allocation.", ".quantity");
  }

  private int used(String rid) {
    int n = 0;
    for (String id : ids("i.", ".owner"))
      if (!state.getProperty("i." + id + ".status").equals("RESOLVED"))
        n +=
            Integer.parseInt(
                state.getProperty("i." + id + ".allocation." + rid + ".quantity", "0"));
    return n;
  }

  private void coordinates(Properties p) {
    for (String k : List.of("lat", "lon")) {
      double v;
      try {
        v = Double.parseDouble(required(p, k));
      } catch (NumberFormatException e) {
        throw new Bad(400, "Invalid coordinates");
      }
      need(
          Double.isFinite(v) && Math.abs(v) <= (k.equals("lat") ? 90 : 180), "Invalid coordinates");
    }
  }

  private synchronized Properties snapshot(String u) {
    Properties p =
        props("ok", "true", "revision", String.valueOf(revision), "user", u, "role", role(u));
    for (String id : ids("i.", ".owner"))
      if (visible(u, id)) {
        String base = "i." + id + ".";
        for (String k : state.stringPropertyNames())
          if (k.startsWith(base) && !k.equals(base + "photo"))
            p.setProperty(k, state.getProperty(k));
        if (state.containsKey(base + "photo")) p.setProperty(base + "hasPhoto", "true");
      }
    if (!role(u).equals("CITIZEN"))
      for (String k : state.stringPropertyNames())
        if (k.startsWith("r.")) p.setProperty(k, state.getProperty(k));
    for (String rid : ids("r.", ".name"))
      if (!role(u).equals("CITIZEN"))
        p.setProperty(
            "r." + rid + ".available",
            String.valueOf(Integer.parseInt(state.getProperty("r." + rid + ".total")) - used(rid)));
    if (role(u).equals("ADMIN"))
      for (String name : ids("u.", ".role")) {
        p.setProperty("u." + name + ".role", role(name));
        p.setProperty("u." + name + ".approved", state.getProperty("u." + name + ".approved"));
      }
    return p;
  }

  private String requestHash(Properties p) {
    StringBuilder b = new StringBuilder();
    for (String k : new TreeSet<>(p.stringPropertyNames()))
      if (!k.equals("token"))
        b.append(k.length())
            .append(':')
            .append(k)
            .append(p.getProperty(k).length())
            .append(':')
            .append(p.getProperty(k));
    return digest(b.toString());
  }

  private Properties mutate(String u, Properties p) throws IOException {
    String op = required(p, "op"), req = required(p, "request");
    need(req.matches("[A-Za-z0-9-]{8,64}"), "Invalid request ID");
    String key = "q." + u + "." + req + ".";
    String hash = requestHash(p);
    if (state.containsKey(key + "hash")) {
      need(
          state.getProperty(key + "hash").equals(hash), "Request ID reused with different content");
      return props(
          "ok",
          "true",
          "id",
          state.getProperty(key + "id", ""),
          "revision",
          String.valueOf(revision),
          "duplicate",
          "true");
    }
    Properties before = (Properties) state.clone();
    long oldRevision = revision;
    try {
      String id = p.getProperty("id", "");
      String base = "i." + id + ".";
      switch (op) {
        case "report" -> {
          roleRequired(u, "CITIZEN");
          coordinates(p);
          String type = field(p, "type", 40), severity = required(p, "severity");
          need(Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity), "Invalid severity");
          String description = p.getProperty("description", "").trim();
          need(description.length() <= 1000, "Description too long");
          boolean sos = Boolean.parseBoolean(p.getProperty("sos", "false"));
          need(sos || !description.isEmpty(), "Description required for ordinary reports");
          need(ids("i.", ".owner").size() < 2000, "Demo incident capacity reached");
          id = UUID.randomUUID().toString();
          base = "i." + id + ".";
          for (String k : List.of("lat", "lon")) state.setProperty(base + k, p.getProperty(k));
          state.setProperty(base + "owner", u);
          state.setProperty(base + "type", type);
          state.setProperty(base + "severity", severity);
          state.setProperty(base + "description", description);
          state.setProperty(base + "sos", String.valueOf(sos));
          state.setProperty(base + "status", "REPORTED");
          state.setProperty(base + "created", Instant.now().toString());
          String photo = p.getProperty("photo", "");
          if (!photo.isEmpty()) {
            byte[] image;
            try {
              image = Base64.getDecoder().decode(photo);
            } catch (Exception e) {
              throw new Bad(400, "Invalid photo");
            }
            need(
                image.length <= 512000
                    && image.length >= 3
                    && image[0] == (byte) 0xff
                    && image[1] == (byte) 0xd8
                    && image[2] == (byte) 0xff,
                "Photo must be JPEG, max 500 KB");
            state.setProperty(base + "photo", photo);
          }
        }
        case "chat" -> {
          incidentAccess(u, id);
          String message = field(p, "message", 1000);
          need(!state.getProperty(base + "status").equals("RESOLVED"), "Incident resolved");
          int n = Integer.parseInt(state.getProperty(base + "chatCount", "0"));
          need(n < 500, "Demo chat capacity reached");
          String m = base + "chat." + String.format(Locale.ROOT, "%04d", n) + ".";
          state.setProperty(m + "user", u);
          state.setProperty(m + "text", message);
          state.setProperty(m + "time", Instant.now().toString());
          state.setProperty(base + "chatCount", String.valueOf(n + 1));
        }
        case "ack" -> {
          roleRequired(u, "DISPATCHER", "ADMIN");
          incidentAccess(u, id);
          need(state.getProperty(base + "status").equals("REPORTED"), "Already acknowledged");
          state.setProperty(base + "status", "ACKNOWLEDGED");
          state.setProperty(base + "ackBy", u);
        }
        case "assign" -> {
          incidentAccess(u, id);
          String rid = required(p, "resource");
          need(state.containsKey("r." + rid + ".name"), "Unknown resource");
          String type = state.getProperty("r." + rid + ".type"),
              owner = state.getProperty("r." + rid + ".owner");
          boolean own = u.equals(owner);
          boolean dispatch =
              role(u).equals("ADMIN") || role(u).equals("DISPATCHER") && !type.equals("BED");
          if (!(dispatch
              || own && role(u).equals("HOSPITAL") && type.equals("BED")
              || own && role(u).equals("RESCUE") && type.equals("EQUIPMENT")))
            throw new Bad(403, "Resource assignment denied");
          String status = state.getProperty(base + "status");
          need(
              !Set.of("REPORTED", "RESOLVED").contains(status),
              "Acknowledge incident before allocation");
          need(
              !state.containsKey(base + "allocation." + rid + ".quantity"),
              "Resource already allocated to this incident");
          int quantity = integer(p, "quantity", 1, 10000);
          need(
              quantity <= Integer.parseInt(state.getProperty("r." + rid + ".total")) - used(rid),
              "Insufficient available resources");
          state.setProperty(base + "allocation." + rid + ".quantity", String.valueOf(quantity));
          state.setProperty(base + "allocation." + rid + ".accepted", "false");
          if (status.equals("ACKNOWLEDGED")) state.setProperty(base + "status", "ASSIGNED");
        }
        case "accept" -> {
          roleRequired(u, "RESCUE", "HOSPITAL");
          incidentAccess(u, id);
          need(!state.getProperty(base + "status").equals("RESOLVED"), "Incident resolved");
          String rid = required(p, "resource");
          need(
              u.equals(state.getProperty("r." + rid + ".owner")),
              "Only the resource owner can accept");
          need(state.containsKey(base + "allocation." + rid + ".quantity"), "Not assigned");
          state.setProperty(base + "allocation." + rid + ".accepted", "true");
          if (state.getProperty(base + "status").equals("ASSIGNED"))
            state.setProperty(base + "status", "ACCEPTED");
        }
        case "progress" -> {
          roleRequired(u, "RESCUE", "HOSPITAL");
          incidentAccess(u, id);
          boolean accepted = false;
          for (String r : allocations(id))
            if (u.equals(state.getProperty("r." + r + ".owner"))
                && state.getProperty(base + "allocation." + r + ".accepted").equals("true"))
              accepted = true;
          need(accepted, "Accept your assignment first");
          String next = required(p, "status"), old = state.getProperty(base + "status");
          need(
              next.equals("EN_ROUTE") && old.equals("ACCEPTED")
                  || next.equals("ON_SCENE") && Set.of("ACCEPTED", "EN_ROUTE").contains(old),
              "Invalid progress transition");
          state.setProperty(base + "status", next);
        }
        case "resolve" -> {
          roleRequired(u, "DISPATCHER", "ADMIN");
          incidentAccess(u, id);
          need(
              !Set.of("REPORTED", "RESOLVED").contains(state.getProperty(base + "status")),
              "Cannot resolve this state");
          state.setProperty(base + "status", "RESOLVED");
          state.setProperty(base + "resolvedBy", u);
        }
        case "location" -> {
          roleRequired(u, "CITIZEN");
          incidentAccess(u, id);
          need(u.equals(state.getProperty(base + "owner")), "Only reporter can update location");
          need(!state.getProperty(base + "status").equals("RESOLVED"), "Incident resolved");
          coordinates(p);
          state.setProperty(base + "lat", p.getProperty("lat"));
          state.setProperty(base + "lon", p.getProperty("lon"));
          state.setProperty(base + "locationTime", Instant.now().toString());
        }
        case "approve" -> {
          roleRequired(u, "ADMIN");
          String name = userName(required(p, "username"));
          need(state.containsKey("u." + name + ".role"), "Unknown account");
          state.setProperty("u." + name + ".approved", "true");
        }
        case "resource" -> {
          roleRequired(u, "ADMIN", "RESCUE", "HOSPITAL");
          String type = required(p, "type");
          need(TYPES.contains(type), "Invalid resource type");
          String owner = userName(required(p, "owner"));
          need(
              Boolean.parseBoolean(state.getProperty("u." + owner + ".approved", "false")),
              "Resource owner must be approved");
          need(
              Set.of("RESCUE", "HOSPITAL").contains(role(owner)),
              "Owner must be rescue team or hospital");
          if (type.equals("BED"))
            need(role(owner).equals("HOSPITAL"), "Beds need a hospital owner");
          else need(role(owner).equals("RESCUE"), "Other resources need a rescue owner");
          if (!role(u).equals("ADMIN")) need(u.equals(owner), "You can manage only your resources");
          if (id.isEmpty()) id = UUID.randomUUID().toString();
          else {
            need(state.containsKey("r." + id + ".name"), "Unknown resource");
            need(
                owner.equals(state.getProperty("r." + id + ".owner"))
                    && type.equals(state.getProperty("r." + id + ".type")),
                "Cannot change owner or type");
          }
          int total = integer(p, "total", 0, 10000);
          need(total >= used(id), "Total cannot be below allocated quantity");
          state.setProperty("r." + id + ".name", field(p, "name", 100));
          state.setProperty("r." + id + ".type", type);
          state.setProperty("r." + id + ".owner", owner);
          state.setProperty("r." + id + ".total", String.valueOf(total));
        }
        default -> throw new Bad(400, "Unknown operation");
      }
      // Frequent location samples are overwrite-only and need no durable dedup record.
      if (!op.equals("location")) {
        state.setProperty(key + "hash", hash);
        state.setProperty(key + "id", id);
      }
      revision++;
      state.setProperty("revision", String.valueOf(revision));
      save();
      notifyAll();
      return props("ok", "true", "id", id, "revision", String.valueOf(revision));
    } catch (Exception e) {
      state.clear();
      state.putAll(before);
      revision = oldRevision;
      throw e;
    }
  }

  private static int integer(Properties p, String k, int min, int max) {
    try {
      int n = Integer.parseInt(required(p, k));
      need(n >= min && n <= max, "Invalid " + k);
      return n;
    } catch (NumberFormatException e) {
      throw new Bad(400, "Invalid " + k);
    }
  }

  private void rateLimit(String key) {
    long now = System.currentTimeMillis();
    long[] b = attempts.computeIfAbsent(key, k -> new long[] {now, 0});
    synchronized (b) {
      if (now - b[0] > 60000) {
        b[0] = now;
        b[1] = 0;
      }
      if (++b[1] > 15) throw new Bad(429, "Too many authentication attempts; wait one minute");
    }
    if (attempts.size() > 10000) attempts.entrySet().removeIf(e -> now - e.getValue()[0] > 120000);
  }

  private Properties request(Properties p, String ip) throws Exception {
    String op = required(p, "op");
    if (Set.of("login", "register").contains(op)) {
      String u = userName(required(p, "username"));
      rateLimit(ip + ":" + u);
      synchronized (this) {
        Properties before = (Properties) state.clone();
        long oldRevision = revision;
        try {
          if (op.equals("register")) {
            need(ids("u.", ".role").size() < 500, "Demo user capacity reached");
            need(!state.containsKey("u." + u + ".role"), "Username already registered");
            String password = required(p, "password");
            passwordValid(password);
            String r = required(p, "role");
            need(ROLES.contains(r) && !r.equals("ADMIN"), "Invalid registration role");
            createUser(u, password, r, r.equals("CITIZEN"));
            revision++;
            state.setProperty("revision", String.valueOf(revision));
            save();
            notifyAll();
            return props(
                "ok",
                "true",
                "message",
                r.equals("CITIZEN")
                    ? "Registered. You can now log in."
                    : "Registered. Ask administrator to approve your account.");
          }
          String salt =
              state.getProperty(
                  "u." + u + ".salt", Base64.getEncoder().encodeToString(new byte[16]));
          String hash = hashPassword(required(p, "password"), salt);
          if (!MessageDigest.isEqual(
              hash.getBytes(StandardCharsets.UTF_8),
              state.getProperty("u." + u + ".password", "").getBytes(StandardCharsets.UTF_8)))
            throw new Bad(401, "Invalid username or password");
          if (!Boolean.parseBoolean(state.getProperty("u." + u + ".approved")))
            throw new Bad(403, "Account awaiting administrator approval");
          long now = System.currentTimeMillis();
          for (String sid : ids("s.", ".user"))
            if (Long.parseLong(state.getProperty("s." + sid + ".expires", "0")) < now) {
              state.remove("s." + sid + ".user");
              state.remove("s." + sid + ".expires");
            }
          String token = secret(), base = "s." + digest(token) + ".";
          state.setProperty(base + "user", u);
          state.setProperty(base + "expires", String.valueOf(now + 7L * 24 * 60 * 60 * 1000));
          save();
          return props("ok", "true", "token", token, "user", u, "role", role(u));
        } catch (Exception e) {
          state.clear();
          state.putAll(before);
          revision = oldRevision;
          throw e;
        }
      }
    }
    String u = authenticate(p);
    if (op.equals("poll")) {
      if (!polls.tryAcquire()) throw new Bad(429, "Too many live connections");
      try {
        long after;
        try {
          after = Long.parseLong(p.getProperty("after", "-1"));
        } catch (Exception e) {
          throw new Bad(400, "Invalid revision");
        }
        synchronized (this) {
          long deadline = System.currentTimeMillis() + 20000;
          while (!closed && revision == after && System.currentTimeMillis() < deadline)
            wait(Math.max(1, deadline - System.currentTimeMillis()));
          authenticate(p);
          return snapshot(u);
        }
      } finally {
        polls.release();
      }
    }
    synchronized (this) {
      switch (op) {
        case "snapshot":
          return snapshot(u);
        case "photo":
          String id = required(p, "id");
          incidentAccess(u, id);
          return props("ok", "true", "photo", state.getProperty("i." + id + ".photo", ""));
        case "logout":
          String b = "s." + digest(required(p, "token")) + ".";
          Properties before = (Properties) state.clone();
          state.remove(b + "user");
          state.remove(b + "expires");
          try {
            save();
          } catch (IOException e) {
            state.clear();
            state.putAll(before);
            throw e;
          }
          notifyAll();
          return props("ok", "true");
        default:
          return mutate(u, p);
      }
    }
  }

  private void handle(HttpExchange e) throws IOException {
    try {
      if (!e.getRequestURI().getPath().equals("/api")) throw new Bad(404, "Unknown path");
      if (!e.getRequestMethod().equals("POST")) throw new Bad(405, "POST required");
      byte[] bytes = e.getRequestBody().readNBytes(800001);
      if (bytes.length > 800000) throw new Bad(413, "Request too large");
      Properties p = new Properties();
      String body = new String(bytes, StandardCharsets.UTF_8);
      for (String pair : body.split("&")) {
        String[] kv = pair.split("=", 2);
        if (kv.length == 2) {
          String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
          need(!p.containsKey(key), "Duplicate field");
          p.setProperty(key, URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
      }
      respond(e, 200, request(p, e.getRemoteAddress().getAddress().getHostAddress()));
    } catch (Bad b) {
      respond(e, b.code, props("error", b.getMessage()));
    } catch (IllegalArgumentException ex) {
      respond(e, 400, props("error", "Invalid request encoding"));
    } catch (Exception ex) {
      System.err.println("Request failed: " + ex.getClass().getSimpleName());
      respond(e, 500, props("error", "Server could not complete request; retry safely"));
    }
  }

  private static void respond(HttpExchange e, int status, Properties p) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    p.store(out, null);
    byte[] b = out.toByteArray();
    e.getResponseHeaders().set("Content-Type", "text/plain; charset=ISO-8859-1");
    e.getResponseHeaders().set("Cache-Control", "no-store");
    e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    e.sendResponseHeaders(status, b.length);
    try (OutputStream stream = e.getResponseBody()) {
      stream.write(b);
    } finally {
      e.close();
    }
  }

  public static void main(String[] args) throws Exception {
    String password = System.getenv("RESCUELINK_ADMIN_PASSWORD");
    Path folder = Path.of(args.length > 0 ? args[0] : "data");
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    String host = args.length > 2 ? args[2] : "127.0.0.1";
    Server server = new Server(folder, host, port, password);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    server.start();
    System.out.println(
        "RescueLink backend http://"
            + host
            + ":"
            + server.port()
            + " (use HTTPS tunnel for internet)");
  }
}
