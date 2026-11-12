package lk.rescuelink;

import android.content.Context;
import java.util.Properties;

final class Session {
  final String url, token, user, role;

  Session(String b, String t, String u, String r) {
    url = b;
    token = t;
    user = u;
    role = r;
  }

  static Session load(Context c) throws Exception {
    String s = Vault.get(c, "session");
    if (s.isEmpty()) return null;
    Properties p = Api.parse(s);
    return new Session(
        p.getProperty("url"), p.getProperty("token"), p.getProperty("user"), p.getProperty("role"));
  }

  void save(Context c) throws Exception {
    Vault.put(
        c,
        "session",
        Api.serialize(Api.fields("url", url, "token", token, "user", user, "role", role)));
  }

  Properties request(String op) {
    return Api.fields("op", op, "token", token);
  }

  String account() {
    return url + "|" + user;
  }
}
