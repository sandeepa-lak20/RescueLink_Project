package rescuelink;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class IntegrationTest {
  static String base;
  static HttpClient client = HttpClient.newHttpClient();

  static Properties p(String... a) {
    return Server.props(a);
  }

  static Properties send(int expected, Properties p) throws Exception {
    var res = raw(p);
    Properties r = new Properties();
    r.load(new ByteArrayInputStream(res.body()));
    check(
        res.statusCode() == expected,
        "HTTP " + res.statusCode() + " expected " + expected + ": " + r);
    return r;
  }

  static HttpResponse<byte[]> raw(Properties p) throws Exception {
    StringJoiner b = new StringJoiner("&");
    for (String k : p.stringPropertyNames())
      b.add(
          URLEncoder.encode(k, StandardCharsets.UTF_8)
              + "="
              + URLEncoder.encode(p.getProperty(k), StandardCharsets.UTF_8));
    return client.send(
        HttpRequest.newBuilder(URI.create(base + "/api"))
            .POST(HttpRequest.BodyPublishers.ofString(b.toString()))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  static Properties action(String t, String op, String... a) {
    Properties r = p("token", t, "op", op, "request", UUID.randomUUID().toString());
    r.putAll(p(a));
    return r;
  }

  static void check(boolean b, String text) {
    if (!b) throw new AssertionError(text);
  }

  static String login(String name) throws Exception {
    return send(200, p("op", "login", "username", name, "password", "TestPassword123!"))
        .getProperty("token");
  }

  static void register(String name, String role) throws Exception {
    send(200, p("op", "register", "username", name, "password", "TestPassword123!", "role", role));
  }

  static Properties snapshot(String token) throws Exception {
    return send(200, p("op", "snapshot", "token", token));
  }

  static String report(String token) throws Exception {
    return send(
            200,
            action(
                token,
                "report",
                "type",
                "Accident",
                "severity",
                "HIGH",
                "description",
                "Crash",
                "lat",
                "6",
                "lon",
                "80"))
        .getProperty("id");
  }

  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("rescuelink-test-");
    Server server = new Server(dir, "127.0.0.1", 0, "TestPassword123!");
    server.start();
    base = "http://127.0.0.1:" + server.port();
    try {
      String admin = login("admin");
      for (String u : List.of("alice", "bob")) register(u, "CITIZEN");
      register("rescue", "RESCUE");
      register("hospital", "HOSPITAL");
      register("dispatch", "DISPATCHER");
      send(403, p("op", "login", "username", "rescue", "password", "TestPassword123!"));
      send(
          400,
          p(
              "op",
              "register",
              "username",
              "eviladmin",
              "password",
              "TestPassword123!",
              "role",
              "ADMIN"));
      for (String u : List.of("rescue", "hospital", "dispatch"))
        send(200, action(admin, "approve", "username", u));
      String citizen = login("alice"),
          bob = login("bob"),
          rescue = login("rescue"),
          hospital = login("hospital"),
          dispatch = login("dispatch");
      Properties report =
          action(
              citizen,
              "report",
              "type",
              "SOS",
              "severity",
              "CRITICAL",
              "description",
              "",
              "sos",
              "true",
              "lat",
              "6.032",
              "lon",
              "80.216");
      String id = send(200, report).getProperty("id");
      check(send(200, report).getProperty("id").equals(id), "Duplicate report");
      Properties changed = (Properties) report.clone();
      changed.setProperty("lat", "7");
      send(400, changed);
      check(!snapshot(bob).containsKey("i." + id + ".owner"), "Privacy violated");
      check(snapshot(admin).getProperty("i." + id + ".sos").equals("true"), "SOS missing");
      send(403, action(citizen, "ack", "id", id));
      send(403, action(bob, "chat", "id", id, "message", "bad"));
      String unit =
          send(
                  200,
                  action(
                      admin,
                      "resource",
                      "name",
                      "Ambulance 1",
                      "type",
                      "AMBULANCE",
                      "owner",
                      "rescue",
                      "total",
                      "1"))
              .getProperty("id");
      String bed =
          send(
                  200,
                  action(
                      hospital,
                      "resource",
                      "name",
                      "Emergency beds",
                      "type",
                      "BED",
                      "owner",
                      "hospital",
                      "total",
                      "2"))
              .getProperty("id");
      send(400, action(dispatch, "assign", "id", id, "resource", unit, "quantity", "1"));
      send(200, action(dispatch, "ack", "id", id));
      send(200, action(dispatch, "assign", "id", id, "resource", unit, "quantity", "1"));
      send(200, action(hospital, "assign", "id", id, "resource", bed, "quantity", "1"));
      send(403, action(citizen, "accept", "id", id, "resource", unit));
      send(400, action(rescue, "progress", "id", id, "status", "EN_ROUTE"));
      send(200, action(rescue, "accept", "id", id, "resource", unit));
      send(200, action(hospital, "accept", "id", id, "resource", bed));
      send(200, action(rescue, "progress", "id", id, "status", "EN_ROUTE"));
      send(200, action(rescue, "progress", "id", id, "status", "ON_SCENE"));
      Properties chat = action(citizen, "chat", "id", id, "message", "At entrance — need help");
      send(200, chat);
      send(200, chat);
      check(snapshot(citizen).getProperty("i." + id + ".chatCount").equals("1"), "Duplicate chat");
      send(200, action(citizen, "location", "id", id, "lat", "6.05", "lon", "80.22"));
      send(400, action(citizen, "location", "id", id, "lat", "NaN", "lon", "80"));
      send(
          400,
          action(
              rescue,
              "resource",
              "id",
              unit,
              "name",
              "Ambulance 1",
              "type",
              "AMBULANCE",
              "owner",
              "rescue",
              "total",
              "0"));
      String second = report(bob);
      send(200, action(dispatch, "ack", "id", second));
      send(400, action(dispatch, "assign", "id", second, "resource", unit, "quantity", "1"));
      send(200, action(dispatch, "resolve", "id", id));
      check(snapshot(dispatch).getProperty("r." + unit + ".available").equals("1"), "Not released");
      send(400, action(citizen, "location", "id", id, "lat", "6", "lon", "80"));
      String third = report(citizen);
      send(200, action(dispatch, "ack", "id", third));
      ExecutorService ex = Executors.newFixedThreadPool(2);
      var futures =
          ex.invokeAll(
              List.<Callable<Integer>>of(
                  () ->
                      raw(action(
                              dispatch, "assign", "id", second, "resource", unit, "quantity", "1"))
                          .statusCode(),
                  () ->
                      raw(action(admin, "assign", "id", third, "resource", unit, "quantity", "1"))
                          .statusCode()));
      check(
          Set.of(futures.get(0).get(), futures.get(1).get()).equals(Set.of(200, 400)),
          "Concurrent allocation failed");
      ex.shutdownNow();
      long rev = Long.parseLong(snapshot(dispatch).getProperty("revision"));
      var update =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return send(
                      200, p("op", "poll", "token", dispatch, "after", String.valueOf(rev)));
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              });
      Thread.sleep(100);
      send(200, action(citizen, "chat", "id", third, "message", "Live update"));
      check(
          Long.parseLong(update.get(3, TimeUnit.SECONDS).getProperty("revision")) > rev,
          "Poll not awakened");
      String photo =
          Base64.getEncoder().encodeToString(new byte[] {(byte) 255, (byte) 216, (byte) 255, 0});
      String photoId =
          send(
                  200,
                  action(
                      citizen,
                      "report",
                      "type",
                      "Test",
                      "severity",
                      "LOW",
                      "description",
                      "Photo",
                      "lat",
                      "6",
                      "lon",
                      "80",
                      "photo",
                      photo))
              .getProperty("id");
      check(
          send(200, p("op", "photo", "token", citizen, "id", photoId))
              .getProperty("photo")
              .equals(photo),
          "Photo lost");
      send(403, p("op", "photo", "token", bob, "id", photoId));
      server.close();
      server = new Server(dir, "127.0.0.1", 0, null);
      server.start();
      base = "http://127.0.0.1:" + server.port();
      check(snapshot(citizen).containsKey("i." + photoId + ".owner"), "Restart lost state/session");
      check(send(200, report).getProperty("id").equals(id), "Restart lost dedup");
      send(200, p("op", "logout", "token", citizen));
      send(401, p("op", "snapshot", "token", citizen));
      Path failureDir = Files.createTempDirectory("rescuelink-failure-");
      String previous = base;
      try (Server failure = new Server(failureDir, "127.0.0.1", 0, "TestPassword123!")) {
        failure.start();
        base = "http://127.0.0.1:" + failure.port();
        String t = login("admin");
        Files.createDirectory(failureDir.resolve("state.tmp"));
        send(
            500,
            p(
                "op",
                "register",
                "username",
                "notstored",
                "password",
                "TestPassword123!",
                "role",
                "CITIZEN"));
        check(!snapshot(t).containsKey("u.notstored.role"), "Failed save leaked state");
      }
      base = previous;
      System.out.println(
          "PASS: authentication/approval, role permissions/privacy, SOS receipts/retry, photos,"
              + " chat, coordinates, incident lifecycle, capacities, concurrent allocation, live"
              + " polling, restart persistence, logout, save rollback.");
    } finally {
      server.close();
    }
  }
}
