import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Chandwad PG - PG finder website for Chandwad city.
 * Single file, zero dependencies. Run:  java Main.java   then open http://localhost:8080
 */
public class Main {
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final Path DATA = Paths.get(System.getenv().getOrDefault("DATA_DIR", "data"));
    static final Path UPLOADS = DATA.resolve("uploads");
    static final Path DB_FILE = DATA.resolve("db.bin");
    static final String RS = "\u20B9";
    static final Object LOCK = new Object();
    static final SecureRandom RNG = new SecureRandom();
    static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();

    // ====================== data ======================
    static class User implements Serializable {
        private static final long serialVersionUID = 1L;
        long id;
        String name, email, phone, college, salt, hash, role; // role: student | owner
    }

    static class Listing implements Serializable {
        private static final long serialVersionUID = 1L;
        long id, ownerId, created;
        String title = "", area = "", address = "", gender = "Any", sharing = "2-sharing", desc = "", photo;
        int rent, deposit, total, free;
        boolean wifi, mess, laundry, power;
    }

    static class Db implements Serializable {
        private static final long serialVersionUID = 1L;
        long nextId = 1;
        List<User> users = new ArrayList<>();
        List<Listing> listings = new ArrayList<>();
    }

    static Db db = new Db();

    static void load() {
        if (!Files.exists(DB_FILE)) return;
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(DB_FILE)))) {
            db = (Db) in.readObject();
        } catch (Exception e) {
            System.out.println("Could not read " + DB_FILE + " (" + e + "). Starting with an empty database.");
        }
    }

    static void save() throws IOException {
        Path tmp = DATA.resolve("db.tmp");
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeObject(db);
        }
        Files.move(tmp, DB_FILE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ====================== main ======================
    public static void main(String[] args) throws Exception {
        Files.createDirectories(UPLOADS);
        load();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
        } catch (BindException e) {
            System.out.println("Port " + PORT + " is already in use. Close the other server (or old copy of this app) and run again.");
            return;
        }
        server.createContext("/", Main::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Chandwad PG is running.  Open  http://localhost:" + PORT);
    }

    static void handle(HttpExchange ex) {
        try {
            synchronized (LOCK) {
                route(ex);
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                String body = "<main class=\"wrap block\"><h1>Something went wrong</h1><p class=\"muted\">" + h(e.toString())
                        + "</p><a class=\"link\" href=\"/\">Back to home</a></main>";
                send(ex, 500, "text/html; charset=utf-8", page("Error", null, body, true).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        } finally {
            ex.close();
        }
    }

    // ====================== routing ======================
    static void route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String method = ex.getRequestMethod();
        User me = currentUser(ex);

        if (method.equals("GET")) {
            switch (path) {
                case "/": html(ex, indexPage(me)); return;
                case "/pgs": html(ex, pgsPage(me, parseForm(ex.getRequestURI().getRawQuery()))); return;
                case "/student/login": html(ex, loginPage(me, "student", null, "")); return;
                case "/owner/login": html(ex, loginPage(me, "owner", null, "")); return;
                case "/student/register": html(ex, registerPage(me, "student", null, new HashMap<>())); return;
                case "/owner/register": html(ex, registerPage(me, "owner", null, new HashMap<>())); return;
                case "/owner/dashboard":
                    if (!isOwner(me)) { redirect(ex, "/owner/login"); return; }
                    html(ex, dashboardPage(me)); return;
                case "/owner/new":
                    if (!isOwner(me)) { redirect(ex, "/owner/login"); return; }
                    html(ex, formPage(me, new Listing(), null)); return;
                case "/style.css":
                    send(ex, 200, "text/css; charset=utf-8", CSS.getBytes(StandardCharsets.UTF_8)); return;
                case "/favicon.ico":
                    send(ex, 204, "image/x-icon", new byte[0]); return;
                default:
            }
            if (path.startsWith("/pgs/")) {
                Listing l = findListing(parseId(path.substring(5)));
                if (l == null) { redirect(ex, "/pgs"); return; }
                html(ex, detailPage(me, l)); return;
            }
            if (path.startsWith("/owner/edit/")) {
                if (!isOwner(me)) { redirect(ex, "/owner/login"); return; }
                Listing l = findListing(parseId(path.substring(12)));
                if (l == null || l.ownerId != me.id) { redirect(ex, "/owner/dashboard"); return; }
                html(ex, formPage(me, l, null)); return;
            }
            if (path.startsWith("/uploads/")) { serveUpload(ex, path.substring(9)); return; }
            notFound(ex, me);
            return;
        }

        if (method.equals("POST")) {
            switch (path) {
                case "/student/login": doLogin(ex, "student"); return;
                case "/owner/login": doLogin(ex, "owner"); return;
                case "/student/register": doRegister(ex, "student"); return;
                case "/owner/register": doRegister(ex, "owner"); return;
                case "/logout": doLogout(ex); return;
                case "/owner/save": doSave(ex, me); return;
                default:
            }
            if (path.startsWith("/owner/delete/")) {
                if (!isOwner(me)) { redirect(ex, "/owner/login"); return; }
                Listing l = findListing(parseId(path.substring(14)));
                if (l != null && l.ownerId == me.id) {
                    db.listings.remove(l);
                    if (l.photo != null) { try { Files.deleteIfExists(UPLOADS.resolve(l.photo)); } catch (IOException ignored) { } }
                    save();
                }
                redirect(ex, "/owner/dashboard");
                return;
            }
        }
        notFound(ex, me);
    }

    static void notFound(HttpExchange ex, User me) throws IOException {
        String body = "<main class=\"wrap block\"><h1>Page not found</h1><p class=\"muted\">This page does not exist.</p><a class=\"link\" href=\"/\">Back to home</a></main>";
        send(ex, 404, "text/html; charset=utf-8", page("Not found", me, body, true).getBytes(StandardCharsets.UTF_8));
    }

    // ====================== actions ======================
    static void doLogin(HttpExchange ex, String role) throws Exception {
        Map<String, String> f = parseForm(readBody(ex));
        String email = f.getOrDefault("email", "").trim().toLowerCase();
        String password = f.getOrDefault("password", "");
        User u = findUserByEmail(email);
        String error = null;
        if (u == null || !checkPassword(password, u)) error = "Email or password is wrong. Check and try again.";
        else if (!u.role.equals(role))
            error = role.equals("owner") ? "This email belongs to a student account. Use the student login."
                    : "This email belongs to a PG owner account. Use the owner login.";
        if (error != null) { html(ex, loginPage(null, role, error, email)); return; }
        startSession(ex, u);
        redirect(ex, role.equals("owner") ? "/owner/dashboard" : "/pgs");
    }

    static void doRegister(HttpExchange ex, String role) throws Exception {
        Map<String, String> f = parseForm(readBody(ex));
        String name = f.getOrDefault("name", "").trim();
        String email = f.getOrDefault("email", "").trim().toLowerCase();
        String phone = f.getOrDefault("phone", "").trim();
        String college = f.getOrDefault("college", "").trim();
        String password = f.getOrDefault("password", "");
        String error = null;
        if (name.isEmpty()) error = "Enter your full name.";
        else if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) error = "Enter a valid email address.";
        else if (!phone.matches("\\d{10}")) error = "Enter a 10-digit mobile number.";
        else if (password.length() < 6) error = "Password needs at least 6 characters.";
        else if (findUserByEmail(email) != null) error = "An account with this email already exists. Log in instead.";
        if (error != null) { html(ex, registerPage(null, role, error, f)); return; }

        User u = new User();
        u.id = db.nextId++;
        u.name = name; u.email = email; u.phone = phone; u.role = role;
        u.college = role.equals("student") ? college : "";
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        u.salt = Base64.getEncoder().encodeToString(salt);
        u.hash = hashPassword(password, salt);
        db.users.add(u);
        save();
        startSession(ex, u);
        redirect(ex, role.equals("owner") ? "/owner/dashboard" : "/pgs");
    }

    static void doLogout(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "sid");
        if (sid != null) SESSIONS.remove(sid);
        ex.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        redirect(ex, "/");
    }

    static void doSave(HttpExchange ex, User me) throws Exception {
        if (!isOwner(me)) { redirect(ex, "/owner/login"); return; }
        Map<String, String> f = parseForm(readBody(ex));
        long id = parseId(f.getOrDefault("id", "0"));
        Listing in = new Listing();
        in.id = id;
        in.title = f.getOrDefault("title", "").trim();
        in.area = f.getOrDefault("area", "").trim();
        in.address = f.getOrDefault("address", "").trim();
        in.gender = oneOf(f.get("gender"), "Any", "Boys", "Girls", "Any");
        in.sharing = oneOf(f.get("sharing"), "2-sharing", "Single", "2-sharing", "3-sharing", "4+ sharing");
        in.rent = toInt(f.get("rent"), -1);
        in.deposit = Math.max(0, toInt(f.get("deposit"), 0));
        in.total = toInt(f.get("total"), -1);
        in.free = toInt(f.get("free"), -1);
        in.wifi = f.containsKey("wifi");
        in.mess = f.containsKey("mess");
        in.laundry = f.containsKey("laundry");
        in.power = f.containsKey("power");
        in.desc = f.getOrDefault("desc", "").trim();
        if (in.desc.length() > 1500) in.desc = in.desc.substring(0, 1500);

        Listing existing = id > 0 ? findListing(id) : null;
        if (id > 0 && (existing == null || existing.ownerId != me.id)) { redirect(ex, "/owner/dashboard"); return; }
        if (existing != null) in.photo = existing.photo;

        String error = null;
        if (in.title.isEmpty()) error = "Enter the PG name.";
        else if (in.area.isEmpty()) error = "Enter the area or locality in Chandwad.";
        else if (in.rent <= 0) error = "Enter the rent per bed per month.";
        else if (in.total < 1) error = "Enter the total number of beds.";
        else if (in.free < 0 || in.free > in.total) error = "Beds free must be between 0 and total beds.";
        if (error != null) { html(ex, formPage(me, in, error)); return; }

        String newPhoto = savePhoto(f.get("photoData"));
        Listing t = existing;
        if (t == null) {
            t = new Listing();
            t.id = db.nextId++;
            t.ownerId = me.id;
            t.created = System.currentTimeMillis();
            db.listings.add(t);
        }
        t.title = in.title; t.area = in.area; t.address = in.address; t.gender = in.gender; t.sharing = in.sharing;
        t.rent = in.rent; t.deposit = in.deposit; t.total = in.total; t.free = in.free;
        t.wifi = in.wifi; t.mess = in.mess; t.laundry = in.laundry; t.power = in.power; t.desc = in.desc;
        if (newPhoto != null) {
            if (t.photo != null) { try { Files.deleteIfExists(UPLOADS.resolve(t.photo)); } catch (IOException ignored) { } }
            t.photo = newPhoto;
        }
        save();
        redirect(ex, "/owner/dashboard");
    }

    static String savePhoto(String dataUrl) throws IOException {
        if (dataUrl == null) return null;
        String prefix = "data:image/jpeg;base64,";
        if (!dataUrl.startsWith(prefix)) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length == 0 || bytes.length > 2_000_000) return null;
        String name = UUID.randomUUID() + ".jpg";
        Files.write(UPLOADS.resolve(name), bytes);
        return name;
    }

    static void serveUpload(HttpExchange ex, String name) throws IOException {
        if (!name.matches("[A-Za-z0-9._-]+") || name.contains("..")) { send(ex, 404, "text/plain", new byte[0]); return; }
        Path p = UPLOADS.resolve(name);
        if (!Files.isRegularFile(p)) { send(ex, 404, "text/plain", new byte[0]); return; }
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        send(ex, 200, "image/jpeg", Files.readAllBytes(p));
    }

    // ====================== auth helpers ======================
    static String hashPassword(String pw, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] b = f.generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 100_000, 256)).getEncoded();
        return Base64.getEncoder().encodeToString(b);
    }

    static boolean checkPassword(String pw, User u) throws Exception {
        String h = hashPassword(pw, Base64.getDecoder().decode(u.salt));
        return MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8), u.hash.getBytes(StandardCharsets.UTF_8));
    }

    static void startSession(HttpExchange ex, User u) {
        byte[] t = new byte[24];
        RNG.nextBytes(t);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
        SESSIONS.put(token, u.id);
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000");
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> hs = ex.getRequestHeaders().get("Cookie");
        if (hs == null) return null;
        for (String h : hs)
            for (String part : h.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        return null;
    }

    static User currentUser(HttpExchange ex) {
        String sid = cookie(ex, "sid");
        if (sid == null) return null;
        Long id = SESSIONS.get(sid);
        if (id == null) return null;
        for (User u : db.users) if (u.id == id) return u;
        return null;
    }

    static boolean isOwner(User u) { return u != null && u.role.equals("owner"); }

    static User findUserByEmail(String email) {
        for (User u : db.users) if (u.email.equalsIgnoreCase(email)) return u;
        return null;
    }

    static User findUser(long id) {
        for (User u : db.users) if (u.id == id) return u;
        return null;
    }

    static Listing findListing(long id) {
        for (Listing l : db.listings) if (l.id == id) return l;
        return null;
    }

    // ====================== http helpers ======================
    static String readBody(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(4_000_000);
        return new String(b, StandardCharsets.UTF_8);
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            m.put(k, v);
        }
        return m;
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (body.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    static void html(HttpExchange ex, String body) throws IOException {
        send(ex, 200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(303, -1);
    }

    static long parseId(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    static int toInt(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    static String oneOf(String v, String dflt, String... allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        return dflt;
    }

    static String h(Object o) {
        if (o == null) return "";
        return o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String money(int n) { return RS + String.format(Locale.US, "%,d", n); }

    static String sel(String cur, String val) { return val.equals(cur) ? " selected" : ""; }

    static String chk(boolean b) { return b ? " checked" : ""; }

    // ====================== html: layout ======================
    static String page(String title, User me, String body, boolean footer) {
        StringBuilder s = new StringBuilder();
        s.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(h(title)).append(" | Chandwad PG</title>")
                .append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">")
                .append("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>")
                .append("<link href=\"https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:wght@600;700;800&family=Hanken+Grotesk:wght@400;500;600&display=swap\" rel=\"stylesheet\">")
                .append("<link rel=\"stylesheet\" href=\"/style.css\"></head><body>");
        s.append("<header class=\"nav\"><div class=\"wrap nav-in\"><a class=\"brand\" href=\"/\"><span class=\"brand-mark\"></span>Chandwad PG</a><nav>")
                .append("<a href=\"/pgs\">Find a PG</a>");
        if (me == null) {
            s.append("<a href=\"/student/login\">Student login</a><a class=\"btn btn-gold btn-sm\" href=\"/owner/login\">Owner login</a>");
        } else {
            if (isOwner(me)) s.append("<a href=\"/owner/dashboard\">My PGs</a>");
            s.append("<span class=\"who\">").append(h(me.name)).append("</span>")
                    .append("<form action=\"/logout\" method=\"post\" class=\"inline\"><button class=\"btn btn-ghost btn-sm\">Log out</button></form>");
        }
        s.append("</nav></div></header>");
        s.append(body);
        if (footer) s.append("<footer class=\"foot\"><div class=\"wrap\">Made for students and PG owners in Chandwad, Maharashtra.</div></footer>");
        s.append("</body></html>");
        return s.toString();
    }

    static String card(Listing l) {
        StringBuilder s = new StringBuilder();
        s.append("<article class=\"card\"><a class=\"card-link\" href=\"/pgs/").append(l.id).append("\"><div class=\"card-photo\">");
        if (l.photo != null) s.append("<img src=\"/uploads/").append(h(l.photo)).append("\" alt=\"\" loading=\"lazy\">");
        else s.append("<div class=\"ph\">").append(h(l.title.isEmpty() ? "P" : l.title.substring(0, 1).toUpperCase())).append("</div>");
        s.append("<span class=\"tag\">").append(l.gender.equals("Any") ? "Boys and Girls" : h(l.gender)).append("</span></div>");
        s.append("<div class=\"card-body\"><h3>").append(h(l.title)).append("</h3><p class=\"loc\">").append(h(l.area)).append("</p>");
        s.append("<p class=\"price\"><strong>").append(money(l.rent)).append("</strong> per bed / month</p>");
        s.append(chips(l, false));
        s.append(beds(l)).append("</div></a></article>");
        return s.toString();
    }

    static String chips(Listing l, boolean big) {
        StringBuilder s = new StringBuilder("<ul class=\"chips" + (big ? " big" : "") + "\">");
        if (l.wifi) s.append("<li>WiFi</li>");
        if (l.mess) s.append("<li>Mess</li>");
        if (l.laundry) s.append("<li>Laundry</li>");
        if (l.power) s.append("<li>Power backup</li>");
        return s.append("</ul>").toString();
    }

    static String beds(Listing l) {
        return l.free == 0 ? "<p class=\"beds full\">Full right now</p>"
                : "<p class=\"beds\">" + l.free + " of " + l.total + " beds free</p>";
    }

    static List<Listing> newestFirst() {
        List<Listing> r = new ArrayList<>(db.listings);
        r.sort((a, b) -> Long.compare(b.id, a.id));
        return r;
    }

    // ====================== html: pages ======================
    static String indexPage(User me) {
        List<Listing> all = newestFirst();
        StringBuilder s = new StringBuilder();
        s.append("<main><section class=\"hero\"><div class=\"wrap hero-grid\"><div>")
                .append("<h1>A room in Chandwad, sorted before the semester starts.</h1>")
                .append("<p class=\"lead\">Browse PGs and hostels run by local owners. Compare rent, food and WiFi, then call the owner directly.</p>")
                .append("<form action=\"/pgs\" class=\"search\"><input name=\"q\" placeholder=\"Search by area or PG name\" aria-label=\"Search PGs\"><button class=\"btn btn-gold\">Search PGs</button></form>")
                .append("<p class=\"hero-note\">").append(all.size()).append(all.size() == 1 ? " PG listed" : " PGs listed").append(" in Chandwad</p></div>")
                .append("<div class=\"doors\">")
                .append("<a class=\"door door-student\" href=\"/student/login\"><h2>I am a student</h2><p>Find a bed, compare rent, call the owner.</p><span class=\"door-cta\">Student login</span></a>")
                .append("<a class=\"door door-owner\" href=\"/owner/login\"><h2>I own a PG</h2><p>List your rooms and fill empty beds.</p><span class=\"door-cta\">Owner login</span></a>")
                .append("</div></div></section>");
        s.append("<section class=\"wrap block\"><div class=\"sec-head\"><h2>Newly listed</h2><a class=\"link\" href=\"/pgs\">See all PGs</a></div>");
        if (all.isEmpty()) {
            s.append("<div class=\"empty\"><h3>No PGs listed yet</h3><p>Own a PG in Chandwad? Add it in two minutes and students will find it here.</p>")
                    .append("<a class=\"btn btn-primary\" href=\"/owner/register\">Register as an owner</a></div>");
        }
        s.append("<div class=\"grid\">");
        for (int i = 0; i < Math.min(6, all.size()); i++) s.append(card(all.get(i)));
        s.append("</div></section>");
        s.append("<section class=\"wrap block steps\"><div><h2>For students</h2><ol>")
                .append("<li>Create a free student account.</li><li>Filter by rent, boys or girls PG, WiFi and mess.</li>")
                .append("<li>Open a PG to see the owner's phone number and call or WhatsApp.</li></ol></div>")
                .append("<div><h2>For PG owners</h2><ol><li>Register as an owner.</li>")
                .append("<li>Add your PG with photo, rent, beds and facilities.</li><li>Update free beds whenever someone joins or leaves.</li></ol></div></section></main>");
        return page("Find a PG in Chandwad", me, s.toString(), true);
    }

    static String pgsPage(User me, Map<String, String> q) {
        String needle = q.getOrDefault("q", "").trim().toLowerCase();
        String gender = q.getOrDefault("gender", "");
        int maxRent = toInt(q.get("maxRent"), -1);
        boolean wifi = q.containsKey("wifi"), mess = q.containsKey("mess");
        List<Listing> res = new ArrayList<>();
        for (Listing l : newestFirst()) {
            if (!needle.isEmpty() && !(l.title + " " + l.area + " " + l.address).toLowerCase().contains(needle)) continue;
            if (!gender.isEmpty() && !l.gender.equals("Any") && !l.gender.equals(gender)) continue;
            if (maxRent > 0 && l.rent > maxRent) continue;
            if (wifi && !l.wifi) continue;
            if (mess && !l.mess) continue;
            res.add(l);
        }
        StringBuilder s = new StringBuilder("<main class=\"wrap block\"><h1 class=\"page-title\">PGs in Chandwad</h1>");
        s.append("<form class=\"filters\" action=\"/pgs\" method=\"get\">")
                .append("<label>Search<input name=\"q\" value=\"").append(h(q.getOrDefault("q", ""))).append("\" placeholder=\"Area or PG name\"></label>")
                .append("<label>PG for<select name=\"gender\"><option value=\"\">Boys or Girls</option>")
                .append("<option value=\"Boys\"").append(sel(gender, "Boys")).append(">Boys</option>")
                .append("<option value=\"Girls\"").append(sel(gender, "Girls")).append(">Girls</option></select></label>")
                .append("<label>Max rent per month<input type=\"number\" name=\"maxRent\" min=\"0\" step=\"500\" value=\"").append(maxRent > 0 ? maxRent : "").append("\" placeholder=\"e.g. 5000\"></label>")
                .append("<div class=\"checks\"><label class=\"check\"><input type=\"checkbox\" name=\"wifi\" value=\"1\"").append(chk(wifi)).append("> WiFi</label>")
                .append("<label class=\"check\"><input type=\"checkbox\" name=\"mess\" value=\"1\"").append(chk(mess)).append("> Mess</label></div>")
                .append("<button class=\"btn btn-primary\">Apply filters</button></form>");
        s.append("<p class=\"muted\">").append(res.size()).append(" result(s)</p>");
        if (res.isEmpty())
            s.append("<div class=\"empty\"><h3>No PG matches these filters</h3><p>Try a higher rent limit or remove the WiFi and mess filters.</p></div>");
        s.append("<div class=\"grid\">");
        for (Listing l : res) s.append(card(l));
        s.append("</div></main>");
        return page("Find a PG", me, s.toString(), true);
    }

    static String detailPage(User me, Listing l) {
        User owner = findUser(l.ownerId);
        boolean canContact = me != null && (me.role.equals("student") || me.id == l.ownerId);
        StringBuilder s = new StringBuilder("<main class=\"wrap block\"><a class=\"link\" href=\"/pgs\">Back to all PGs</a><div class=\"detail\"><div>");
        s.append("<div class=\"detail-photo\">");
        if (l.photo != null) s.append("<img src=\"/uploads/").append(h(l.photo)).append("\" alt=\"\">");
        else s.append("<div class=\"ph\">").append(h(l.title.substring(0, 1).toUpperCase())).append("</div>");
        s.append("</div><h1>").append(h(l.title)).append("</h1><p class=\"loc\">").append(h(l.area))
                .append(l.address.isEmpty() ? "" : ", " + h(l.address)).append("</p>");
        s.append("<dl class=\"facts\"><div><dt>For</dt><dd>").append(l.gender.equals("Any") ? "Boys and Girls" : h(l.gender)).append("</dd></div>")
                .append("<div><dt>Room type</dt><dd>").append(h(l.sharing)).append("</dd></div>")
                .append("<div><dt>Deposit</dt><dd>").append(money(l.deposit)).append("</dd></div>")
                .append("<div><dt>Beds free</dt><dd>").append(l.free).append(" of ").append(l.total).append("</dd></div></dl>");
        s.append(chips(l, true));
        if (!l.desc.isEmpty()) s.append("<h2>About this PG</h2><p class=\"about\">").append(h(l.desc)).append("</p>");
        s.append("</div><aside class=\"contact\"><p class=\"price\"><strong>").append(money(l.rent)).append("</strong> per bed / month</p>");
        if (canContact && owner != null) {
            s.append("<p class=\"muted\">Listed by</p><p class=\"owner-name\">").append(h(owner.name)).append("</p>")
                    .append("<a class=\"btn btn-primary btn-block\" href=\"tel:").append(h(owner.phone)).append("\">Call ").append(h(owner.phone)).append("</a>")
                    .append("<a class=\"btn btn-line btn-block\" target=\"_blank\" rel=\"noopener\" href=\"https://wa.me/91").append(h(owner.phone)).append("\">WhatsApp</a>");
        } else {
            s.append("<p>Log in as a student to see the owner's phone number.</p>")
                    .append("<a class=\"btn btn-gold btn-block\" href=\"/student/login\">Student login</a>")
                    .append("<p class=\"alt\">No account yet? <a href=\"/student/register\">Create one in a minute</a></p>");
        }
        s.append("</aside></div></main>");
        return page(l.title, me, s.toString(), true);
    }

    static String authSide(String role, boolean register) {
        boolean st = role.equals("student");
        String h1, p;
        if (register) {
            h1 = st ? "Your PG search starts here." : "List your PG for free.";
            p = st ? "A free account lets you see owner contact details for every PG in Chandwad."
                    : "Add photos, rent and facilities. Students call you directly, no middleman.";
        } else {
            h1 = st ? "Find a bed that feels like home." : "Fill your empty beds.";
            p = st ? "Log in to see owner phone numbers for every PG near your college."
                    : "Log in to add your PG, change rent and keep your free beds up to date.";
        }
        return "<aside class=\"auth-side\"><div class=\"arch\"></div><h1>" + h1 + "</h1><p>" + p + "</p></aside>";
    }

    static String loginPage(User me, String role, String error, String email) {
        boolean st = role.equals("student");
        StringBuilder s = new StringBuilder("<main class=\"auth " + (st ? "auth-student" : "auth-owner") + "\">");
        s.append(authSide(role, false)).append("<section class=\"auth-form\"><h2>").append(st ? "Student login" : "PG owner login").append("</h2>");
        if (error != null) s.append("<p class=\"alert\">").append(h(error)).append("</p>");
        s.append("<form action=\"/").append(role).append("/login\" method=\"post\">")
                .append("<label>Email<input type=\"email\" name=\"email\" value=\"").append(h(email)).append("\" required autocomplete=\"email\"></label>")
                .append("<label>Password<input type=\"password\" name=\"password\" required autocomplete=\"current-password\"></label>")
                .append("<button class=\"btn btn-primary btn-block\">Log in</button></form>")
                .append("<p class=\"alt\">New here? <a href=\"/").append(role).append("/register\">").append(st ? "Create a student account" : "Register your PG").append("</a></p>")
                .append(st ? "<p class=\"alt\">Own a PG? <a href=\"/owner/login\">Owner login</a></p>"
                        : "<p class=\"alt\">Looking for a PG? <a href=\"/student/login\">Student login</a></p>")
                .append("</section></main>");
        return page("Login", me, s.toString(), false);
    }

    static String registerPage(User me, String role, String error, Map<String, String> f) {
        boolean st = role.equals("student");
        StringBuilder s = new StringBuilder("<main class=\"auth " + (st ? "auth-student" : "auth-owner") + "\">");
        s.append(authSide(role, true)).append("<section class=\"auth-form\"><h2>").append(st ? "Create student account" : "Create owner account").append("</h2>");
        if (error != null) s.append("<p class=\"alert\">").append(h(error)).append("</p>");
        s.append("<form action=\"/").append(role).append("/register\" method=\"post\">")
                .append("<label>Full name<input name=\"name\" value=\"").append(h(f.get("name"))).append("\" required></label>")
                .append("<label>Email<input type=\"email\" name=\"email\" value=\"").append(h(f.get("email"))).append("\" required autocomplete=\"email\"></label>")
                .append("<label>Mobile number<input name=\"phone\" value=\"").append(h(f.get("phone"))).append("\" inputmode=\"numeric\" maxlength=\"10\" placeholder=\"10 digits\" required></label>");
        if (st)
            s.append("<label>College<input name=\"college\" value=\"").append(h(f.get("college"))).append("\" placeholder=\"Your college in Chandwad\"></label>");
        s.append("<label>Password<input type=\"password\" name=\"password\" minlength=\"6\" required autocomplete=\"new-password\"></label>")
                .append("<button class=\"btn btn-primary btn-block\">Create account</button></form>")
                .append("<p class=\"alt\">Already registered? <a href=\"/").append(role).append("/login\">Log in</a></p></section></main>");
        return page("Create account", me, s.toString(), false);
    }

    static String dashboardPage(User me) {
        List<Listing> mine = new ArrayList<>();
        for (Listing l : newestFirst()) if (l.ownerId == me.id) mine.add(l);
        StringBuilder s = new StringBuilder("<main class=\"wrap block\"><div class=\"sec-head\"><div><h1 class=\"page-title\">Your PGs</h1>")
                .append("<p class=\"muted\">Students see these under Find a PG.</p></div><a class=\"btn btn-gold\" href=\"/owner/new\">Add a PG</a></div>");
        if (mine.isEmpty())
            s.append("<div class=\"empty\"><h3>You have not listed a PG yet</h3><p>Add your first PG with photo, rent and facilities. It goes live right away.</p>")
                    .append("<a class=\"btn btn-primary\" href=\"/owner/new\">Add your first PG</a></div>");
        s.append("<div class=\"rows\">");
        for (Listing l : mine) {
            s.append("<article class=\"row\"><div class=\"row-main\"><h3>").append(h(l.title)).append("</h3><p class=\"muted\">")
                    .append(h(l.area)).append(", ").append(money(l.rent)).append(" per bed / month</p>").append(beds(l)).append("</div>")
                    .append("<div class=\"row-actions\"><a class=\"btn btn-line btn-sm\" href=\"/pgs/").append(l.id).append("\">View</a>")
                    .append("<a class=\"btn btn-line btn-sm\" href=\"/owner/edit/").append(l.id).append("\">Edit</a>")
                    .append("<form class=\"inline\" method=\"post\" action=\"/owner/delete/").append(l.id).append("\" onsubmit=\"return confirm('Delete this PG listing?');\">")
                    .append("<button class=\"btn btn-danger btn-sm\">Delete</button></form></div></article>");
        }
        s.append("</div></main>");
        return page("My PGs", me, s.toString(), true);
    }

    static String formPage(User me, Listing l, String error) {
        boolean editing = l.id > 0;
        StringBuilder s = new StringBuilder("<main class=\"wrap block narrow\"><h1 class=\"page-title\">").append(editing ? "Edit PG" : "Add a PG").append("</h1>");
        if (error != null) s.append("<p class=\"alert\">").append(h(error)).append("</p>");
        s.append("<form method=\"post\" action=\"/owner/save\"><input type=\"hidden\" name=\"id\" value=\"").append(l.id).append("\">")
                .append("<label>PG name<input name=\"title\" value=\"").append(h(l.title)).append("\" placeholder=\"e.g. Shree Sai Boys PG\" required></label>")
                .append("<div class=\"row2\"><label>Area in Chandwad<input name=\"area\" value=\"").append(h(l.area)).append("\" placeholder=\"Locality or landmark\" required></label>")
                .append("<label>Full address (optional)<input name=\"address\" value=\"").append(h(l.address)).append("\"></label></div>")
                .append("<div class=\"row2\"><label>PG for<select name=\"gender\">")
                .append("<option value=\"Boys\"").append(sel(l.gender, "Boys")).append(">Boys only</option>")
                .append("<option value=\"Girls\"").append(sel(l.gender, "Girls")).append(">Girls only</option>")
                .append("<option value=\"Any\"").append(sel(l.gender, "Any")).append(">Boys and Girls</option></select></label>")
                .append("<label>Room type<select name=\"sharing\">")
                .append("<option value=\"Single\"").append(sel(l.sharing, "Single")).append(">Single room</option>")
                .append("<option value=\"2-sharing\"").append(sel(l.sharing, "2-sharing")).append(">2-sharing</option>")
                .append("<option value=\"3-sharing\"").append(sel(l.sharing, "3-sharing")).append(">3-sharing</option>")
                .append("<option value=\"4+ sharing\"").append(sel(l.sharing, "4+ sharing")).append(">4 or more sharing</option></select></label></div>")
                .append("<div class=\"row2\"><label>Rent per bed per month (").append(RS).append(")<input type=\"number\" name=\"rent\" min=\"1\" value=\"").append(l.rent > 0 ? l.rent : "").append("\" required></label>")
                .append("<label>Deposit (").append(RS).append(")<input type=\"number\" name=\"deposit\" min=\"0\" value=\"").append(l.deposit > 0 ? l.deposit : "").append("\"></label></div>")
                .append("<div class=\"row2\"><label>Total beds<input type=\"number\" name=\"total\" min=\"1\" value=\"").append(l.total > 0 ? l.total : "").append("\" required></label>")
                .append("<label>Beds free right now<input type=\"number\" name=\"free\" min=\"0\" value=\"").append(l.free >= 0 && l.total > 0 ? String.valueOf(l.free) : "").append("\" required></label></div>")
                .append("<fieldset class=\"checks\"><legend>Facilities</legend>")
                .append("<label class=\"check\"><input type=\"checkbox\" name=\"wifi\" value=\"1\"").append(chk(l.wifi)).append("> WiFi</label>")
                .append("<label class=\"check\"><input type=\"checkbox\" name=\"mess\" value=\"1\"").append(chk(l.mess)).append("> Mess / food</label>")
                .append("<label class=\"check\"><input type=\"checkbox\" name=\"laundry\" value=\"1\"").append(chk(l.laundry)).append("> Laundry</label>")
                .append("<label class=\"check\"><input type=\"checkbox\" name=\"power\" value=\"1\"").append(chk(l.power)).append("> Power backup</label></fieldset>")
                .append("<label>About your PG<textarea name=\"desc\" rows=\"4\" maxlength=\"1500\" placeholder=\"Distance from college, rules, food timings, anything students should know\">").append(h(l.desc)).append("</textarea></label>")
                .append("<label>Photo <span class=\"hint\">(JPG or PNG, resized automatically)</span><input type=\"file\" id=\"photo\" accept=\"image/*\"></label>")
                .append("<input type=\"hidden\" name=\"photoData\" id=\"photoData\">")
                .append("<img id=\"preview\" class=\"preview\" alt=\"Photo preview\">");
        if (l.photo != null)
            s.append("<p class=\"hint\">Current photo is kept unless you choose a new one.</p><img class=\"preview\" style=\"display:block\" src=\"/uploads/").append(h(l.photo)).append("\" alt=\"\">");
        s.append("<div class=\"form-actions\"><button class=\"btn btn-primary\">").append(editing ? "Save changes" : "Publish PG").append("</button>")
                .append("<a class=\"btn btn-line\" href=\"/owner/dashboard\">Cancel</a></div></form>");
        s.append(PHOTO_JS).append("</main>");
        return page(editing ? "Edit PG" : "Add a PG", me, s.toString(), true);
    }

    static final String PHOTO_JS = """
            <script>
            document.getElementById('photo').addEventListener('change', function (e) {
              var f = e.target.files[0];
              if (!f) return;
              var r = new FileReader();
              r.onload = function () {
                var img = new Image();
                img.onload = function () {
                  var max = 900, s = Math.min(1, max / Math.max(img.width, img.height));
                  var c = document.createElement('canvas');
                  c.width = Math.round(img.width * s);
                  c.height = Math.round(img.height * s);
                  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
                  var d = c.toDataURL('image/jpeg', 0.8);
                  document.getElementById('photoData').value = d;
                  var p = document.getElementById('preview');
                  p.src = d;
                  p.style.display = 'block';
                };
                img.src = r.result;
              };
              r.readAsDataURL(f);
            });
            </script>
            """;

    // ====================== css ======================
    static final String CSS = """
:root{
  --ink:#0E2B28; --pine:#1D4A44; --gold:#F4B223; --brick:#C9482B;
  --mist:#EDF2EF; --line:#D5DFDA; --text:#17221F; --muted:#5B6A65;
}
*{box-sizing:border-box;margin:0}
html{scroll-behavior:smooth}
body{font-family:'Hanken Grotesk',system-ui,sans-serif;color:var(--text);background:#fff;line-height:1.55}
h1,h2,h3,.brand{font-family:'Bricolage Grotesque',system-ui,sans-serif;letter-spacing:-.02em;line-height:1.12}
a{color:inherit}
img{max-width:100%;display:block}
:focus-visible{outline:3px solid var(--gold);outline-offset:2px}
.wrap{max-width:1120px;margin:0 auto;padding:0 20px}
.narrow{max-width:720px}
.block{padding-top:44px;padding-bottom:20px}
.muted{color:var(--muted)}
.link{font-weight:600;text-decoration:underline;text-underline-offset:3px}
.inline{display:inline}

/* nav */
.nav{background:var(--ink);color:#fff;position:sticky;top:0;z-index:20}
.nav-in{display:flex;align-items:center;justify-content:space-between;height:64px}
.brand{display:flex;align-items:center;gap:10px;font-size:1.25rem;font-weight:800;text-decoration:none}
.brand-mark{width:22px;height:28px;background:var(--gold);border-radius:11px 11px 4px 4px}
.nav nav{display:flex;align-items:center;gap:20px}
.nav nav a{text-decoration:none;font-weight:500}
.nav nav a:not(.btn):hover{color:var(--gold)}
.who{opacity:.75;font-size:.92rem}

/* buttons */
.btn{display:inline-block;padding:.7rem 1.3rem;border-radius:10px;border:1.5px solid transparent;font:600 .95rem 'Hanken Grotesk',sans-serif;text-decoration:none;text-align:center;cursor:pointer;transition:transform .15s}
.btn:hover{transform:translateY(-1px)}
.btn-sm{padding:.4rem .9rem;font-size:.88rem}
.btn-block{display:block;width:100%;margin-top:12px}
.btn-gold{background:var(--gold);color:var(--ink)}
.btn-primary{background:var(--ink);color:#fff}
.btn-line{border-color:var(--ink);color:var(--ink);background:transparent}
.btn-ghost{border-color:rgba(255,255,255,.4);color:#fff;background:transparent}
.btn-danger{background:var(--brick);color:#fff}

/* hero */
.hero{background:var(--ink);color:#fff;padding:64px 0 72px}
.hero-grid{display:grid;grid-template-columns:1.1fr 1fr;gap:48px;align-items:center}
.hero h1{font-size:clamp(2.2rem,5vw,3.5rem);font-weight:800}
.lead{margin:18px 0 26px;max-width:46ch;color:rgba(255,255,255,.78);font-size:1.1rem}
.search{display:flex;gap:10px;max-width:480px}
.search input{flex:1;min-width:0;padding:.8rem 1rem;border-radius:10px;border:0;font:inherit}
.hero-note{margin-top:14px;color:rgba(255,255,255,.65);font-size:.92rem}
.doors{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.door{min-height:340px;padding:28px 20px;border-radius:150px 150px 16px 16px;display:flex;flex-direction:column;justify-content:flex-end;text-decoration:none;transition:transform .2s}
.door:hover{transform:translateY(-4px)}
.door h2{font-size:1.5rem;font-weight:800}
.door p{margin-top:6px;font-size:.95rem}
.door-cta{margin-top:14px;font-weight:600;text-decoration:underline;text-underline-offset:3px}
.door-student{background:var(--gold);color:var(--ink)}
.door-owner{background:var(--pine);color:#fff;border:1px solid rgba(255,255,255,.2)}

/* sections */
.sec-head{display:flex;justify-content:space-between;align-items:end;gap:16px;margin-bottom:22px}
.sec-head h2,.page-title{font-size:1.9rem;font-weight:800}
.page-title{margin-bottom:18px}
.sec-head .page-title{margin-bottom:4px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:20px;margin-top:16px}
.steps{display:grid;grid-template-columns:1fr 1fr;gap:40px;padding-top:56px}
.steps h2{font-size:1.4rem;margin-bottom:12px}
.steps ol{padding-left:1.2rem}
.steps li{margin-bottom:8px}
.empty{background:var(--mist);border-radius:14px;padding:32px;text-align:center;margin:16px 0}
.empty h3{font-size:1.3rem;margin-bottom:6px}
.empty p{margin-bottom:16px;color:var(--muted)}

/* cards */
.card{border:1px solid var(--line);border-radius:14px;overflow:hidden;background:#fff;transition:border-color .15s}
.card:hover{border-color:var(--pine)}
.card-link{text-decoration:none;display:block}
.card-photo{height:170px;position:relative;background:var(--mist)}
.card-photo img{width:100%;height:100%;object-fit:cover}
.ph{width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:var(--pine);color:var(--gold);font:800 3.2rem 'Bricolage Grotesque',sans-serif}
.tag{position:absolute;top:10px;left:10px;background:var(--ink);color:#fff;font-size:.76rem;font-weight:600;padding:3px 10px;border-radius:99px}
.card-body{padding:16px}
.card-body h3{font-size:1.15rem}
.loc{color:var(--muted);margin:2px 0 10px}
.price strong{font:800 1.35rem 'Bricolage Grotesque',sans-serif}
.chips{list-style:none;display:flex;flex-wrap:wrap;gap:6px;padding:0;margin:10px 0}
.chips li{background:var(--mist);border-radius:6px;padding:2px 9px;font-size:.8rem;font-weight:500}
.chips.big li{font-size:.9rem;padding:4px 12px}
.beds{color:var(--pine);font-weight:600;font-size:.88rem}
.beds.full{color:var(--brick)}

/* filters */
.filters{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:14px;align-items:end;background:var(--mist);padding:18px;border-radius:14px;margin-bottom:18px}
.filters label{margin:0}

/* forms */
label{display:block;font-weight:600;font-size:.9rem;margin-bottom:14px}
input,select,textarea{width:100%;margin-top:6px;padding:.65rem .8rem;border:1px solid #BFCCC6;border-radius:10px;font:400 1rem 'Hanken Grotesk',sans-serif;background:#fff;color:var(--text)}
input[type=checkbox]{width:auto;margin:0}
.row2{display:grid;grid-template-columns:1fr 1fr;gap:14px}
.checks{display:flex;flex-wrap:wrap;gap:6px 20px;border:0;margin-bottom:14px}
.checks legend{font-weight:600;font-size:.9rem;margin-bottom:8px}
.check{display:inline-flex;align-items:center;gap:8px;font-weight:500;margin:0}
.form-actions{display:flex;gap:12px;margin-top:8px}
.alert{background:#FBE9E4;color:#8A2B14;border-left:4px solid var(--brick);padding:10px 14px;border-radius:8px;margin-bottom:16px}

/* auth */
.auth{display:grid;grid-template-columns:1fr 1fr;min-height:calc(100vh - 64px)}
.auth-side{padding:56px 48px;display:flex;flex-direction:column;justify-content:center}
.auth-side h1{font-size:2.4rem;font-weight:800;max-width:14ch}
.auth-side p{margin-top:14px;max-width:38ch}
.arch{width:90px;height:120px;border-radius:45px 45px 8px 8px;margin-bottom:28px}
.auth-student .auth-side{background:var(--gold);color:var(--ink)}
.auth-student .arch{background:var(--ink)}
.auth-owner .auth-side{background:var(--pine);color:#fff}
.auth-owner .arch{background:var(--gold)}
.auth-form{padding:56px 48px;max-width:500px;width:100%;margin:auto}
.auth-form h2{font-size:1.8rem;font-weight:800;margin-bottom:20px}
.alt{margin-top:14px;color:var(--muted);font-size:.95rem}
.alt a{color:var(--ink);font-weight:600}

/* detail */
.detail{display:grid;grid-template-columns:1.5fr 1fr;gap:36px;margin-top:18px;align-items:start}
.detail-photo{height:360px;border-radius:14px;overflow:hidden;margin-bottom:20px}
.detail-photo img{width:100%;height:100%;object-fit:cover}
.detail h1{font-size:2.2rem;font-weight:800}
.detail h2{margin:24px 0 8px;font-size:1.3rem}
.facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:12px;margin:18px 0}
.facts div{border:1px solid var(--line);border-radius:10px;padding:10px 14px}
.facts dt{font-size:.82rem;color:var(--muted)}
.facts dd{font-weight:600}
.about{white-space:pre-line;max-width:70ch}
.contact{border:2px solid var(--ink);border-radius:14px;padding:22px;position:sticky;top:84px}
.owner-name{font:700 1.3rem 'Bricolage Grotesque',sans-serif;margin-bottom:6px}

/* owner dashboard */
.rows{display:grid;gap:12px;margin-top:16px}
.row{display:flex;justify-content:space-between;align-items:center;gap:16px;border:1px solid var(--line);border-radius:14px;padding:16px 18px;flex-wrap:wrap}
.row h3{font-size:1.15rem}
.row-actions{display:flex;gap:8px;align-items:center}

.foot{background:var(--ink);color:rgba(255,255,255,.7);padding:26px 0;margin-top:64px;font-size:.92rem}

@media (max-width:860px){
  .hero-grid,.steps,.detail,.auth{grid-template-columns:1fr}
  .auth-side{padding:36px 24px}
  .auth-side h1{font-size:1.9rem}
  .arch{width:60px;height:80px;border-radius:30px 30px 6px 6px;margin-bottom:18px}
  .auth-form{padding:32px 24px}
  .door{min-height:260px}
  .contact{position:static}
  .row2{grid-template-columns:1fr}
  .nav nav{gap:12px;font-size:.9rem}
  .who{display:none}
}
@media (prefers-reduced-motion:reduce){*{transition:none!important}}

.preview{display:none;max-height:170px;border-radius:10px;margin-top:8px}
.hint{color:var(--muted);font-size:.85rem;font-weight:400}

""";
}
