package com.urlshortener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shortie — Micro Link Engine
 */
public class Main {

    private static final int PORT = Integer.parseInt(System.getProperty("port", "8080"));
    private static final Pattern URL_FIELD = Pattern.compile("\"url\"\\s*:\\s*\"(.*?)\"");
    private static final Pattern ALIAS_FIELD = Pattern.compile("\"alias\"\\s*:\\s*\"(.*?)\"");

    private static UrlStore store;
    private static Path cssPath;
    private static Path logoPath;
    private static String lanIp;
    private static byte[] logoPngBytes;
    private static String logoDataUri;

    static {
        try {
            logoPath = Path.of("logo.png");
            if (Files.exists(logoPath)) {
                logoPngBytes = Files.readAllBytes(logoPath);
                logoDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(logoPngBytes);
            } else {
                logoPngBytes = new byte[0];
                logoDataUri = "";
            }
        } catch (Exception e) {
            logoPngBytes = new byte[0];
            logoDataUri = "";
        }
    }

    public static void main(String[] args) throws IOException {
        Path dataFile = Path.of(System.getProperty("dataFile", "urlshortener-data.txt"));
        cssPath = Path.of("style.css");
        store = new UrlStore(dataFile);
        lanIp = getLocalIpAddress();

        // Bind to 0.0.0.0 for cross-device & cloud deployments
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/api/shorten", Main::handleShorten);
        server.createContext("/api/check-alias/", Main::handleCheckAlias);
        server.createContext("/api/stats/", Main::handleStats);
        server.createContext("/api/links", Main::handleLinksRouter);
        server.createContext("/style.css", Main::handleStyleCss);
        server.createContext("/logo.png", Main::handleLogoPng);

        // Safari & Apple tab icon endpoints (serves exact user logo PNG)
        server.createContext("/favicon.ico", Main::handleLogoPng);
        server.createContext("/favicon.png", Main::handleLogoPng);
        server.createContext("/apple-touch-icon.png", Main::handleLogoPng);
        server.createContext("/apple-touch-icon-precomposed.png", Main::handleLogoPng);
        server.createContext("/favicon.svg", Main::handleFaviconSvg);

        server.createContext("/", Main::handleRootOrRedirect);
        server.setExecutor(Executors.newFixedThreadPool(16));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Flushing short URL store data on shutdown...");
            store.flush();
        }));

        server.start();
        System.out.println("=================================================");
        System.out.println("Shortie Engine Ready & Running!");
        System.out.println("  • Laptop Local: http://localhost:" + PORT);
        System.out.println("  • Mobile Wi-Fi: http://" + lanIp + ":" + PORT);
        System.out.println("=================================================");
    }

    private static String getLocalIpAddress() {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {}
        return "localhost";
    }

    // ---- Handlers ----

    private static void handleCheckAlias(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rawAlias = path.substring("/api/check-alias/".length());
        String alias = URLDecoder.decode(rawAlias, StandardCharsets.UTF_8);
        boolean available = store.isAliasAvailable(alias);
        String suggestion = store.suggestAvailableAlias(alias);
        String json = String.format(
                "{\"alias\":\"%s\",\"available\":%b,\"suggestion\":\"%s\"}",
                escape(alias), available, escape(suggestion)
        );
        sendJson(exchange, 200, json);
    }

    private static void handleLogoPng(HttpExchange exchange) throws IOException {
        if (logoPngBytes != null && logoPngBytes.length > 0) {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.sendResponseHeaders(200, logoPngBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(logoPngBytes);
            }
        } else {
            sendJson(exchange, 404, "{\"error\":\"logo.png not found\"}");
        }
    }

    private static void handleFaviconSvg(HttpExchange exchange) throws IOException {
        String svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 40" width="160" height="40">
              <style>
                .t { font-family: -apple-system, BlinkMacSystemFont, sans-serif; font-weight: 900; font-size: 28px; fill: #1e1926; letter-spacing: -1.5px; }
              </style>
              <text x="0" y="30" class="t">shortie</text>
            </svg>
            """;
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void handleStyleCss(HttpExchange exchange) throws IOException {
        if (Files.exists(cssPath)) {
            byte[] bytes = Files.readAllBytes(cssPath);
            exchange.getResponseHeaders().set("Content-Type", "text/css; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            sendJson(exchange, 404, "{\"error\":\"style.css not found\"}");
        }
    }

    private static void handleShorten(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Use POST with a JSON body: {\\\"url\\\":\\\"...\\\"}\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String url = extract(URL_FIELD, body);
        String alias = extract(ALIAS_FIELD, body);

        if (url == null || url.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Missing required field: url\"}");
            return;
        }
        if (!isValidUrl(url)) {
            sendJson(exchange, 400, "{\"error\":\"Invalid URL. Must start with http:// or https://\"}");
            return;
        }

        String reqBaseUrl = getBaseUrl(exchange);

        try {
            UrlEntry entry = store.create(url, alias);
            String json = String.format(
                    "{\"code\":\"%s\",\"shortUrl\":\"%s/%s\",\"originalUrl\":\"%s\",\"createdAt\":\"%s\",\"message\":\"Short link generated\"}",
                    entry.getCode(), reqBaseUrl, entry.getCode(),
                    escape(entry.getOriginalUrl()), Instant.ofEpochMilli(entry.getCreatedAt()));
            sendJson(exchange, 201, json);
        } catch (IllegalArgumentException e) {
            String suggestion = store.suggestAvailableAlias(alias);
            String json = String.format(
                    "{\"error\":\"%s\",\"suggestion\":\"%s\"}",
                    escape(e.getMessage()), escape(suggestion));
            sendJson(exchange, 409, json);
        }
    }

    private static void handleStats(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rawCode = path.substring("/api/stats/".length());
        String code = URLDecoder.decode(rawCode, StandardCharsets.UTF_8);
        UrlEntry entry = store.get(code);
        if (entry == null) {
            sendJson(exchange, 404, "{\"error\":\"No such short code: " + escape(code) + "\"}");
            return;
        }
        String json = String.format(
                "{\"code\":\"%s\",\"originalUrl\":\"%s\",\"clicks\":%d,\"createdAt\":\"%s\"}",
                entry.getCode(), escape(entry.getOriginalUrl()), entry.getClicks(),
                Instant.ofEpochMilli(entry.getCreatedAt()));
        sendJson(exchange, 200, json);
    }

    private static void handleLinksRouter(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            handleListLinks(exchange);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            handleDeleteLink(exchange);
        } else {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    private static void handleListLinks(HttpExchange exchange) throws IOException {
        var entries = store.getAll();
        String reqBaseUrl = getBaseUrl(exchange);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            UrlEntry entry = entries.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(
                    "{\"code\":\"%s\",\"shortUrl\":\"%s/%s\",\"originalUrl\":\"%s\",\"clicks\":%d,\"createdAt\":\"%s\"}",
                    entry.getCode(), reqBaseUrl, entry.getCode(),
                    escape(entry.getOriginalUrl()), entry.getClicks(),
                    Instant.ofEpochMilli(entry.getCreatedAt())
            ));
        }
        sb.append("]");
        sendJson(exchange, 200, sb.toString());
    }

    private static void handleDeleteLink(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith("/api/links/")) {
            sendJson(exchange, 400, "{\"error\":\"Missing short code\"}");
            return;
        }
        String rawCode = path.substring("/api/links/".length());
        String code = URLDecoder.decode(rawCode, StandardCharsets.UTF_8);
        boolean removed = store.delete(code);
        if (removed) {
            sendJson(exchange, 200, "{\"success\":true}");
        } else {
            sendJson(exchange, 404, "{\"error\":\"Link not found: " + escape(code) + "\"}");
        }
    }

    private static void handleRootOrRedirect(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/") || path.isEmpty()) {
            sendHtml(exchange, 200, INDEX_HTML);
            return;
        }

        String code = path.startsWith("/") ? path.substring(1) : path;
        code = URLDecoder.decode(code, StandardCharsets.UTF_8);

        UrlEntry entry = store.get(code);
        if (entry == null) {
            sendJson(exchange, 404, "{\"error\":\"No such short code: " + escape(code) + "\"}");
            return;
        }
        store.recordClick(code);
        exchange.getResponseHeaders().add("Location", entry.getOriginalUrl());
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static final String SYSTEM_BASE_URL = System.getProperty("baseUrl", "").trim();

    // ---- Helpers ----

    private static String getBaseUrl(HttpExchange exchange) {
        if (!SYSTEM_BASE_URL.isEmpty()) {
            return SYSTEM_BASE_URL.endsWith("/") ? SYSTEM_BASE_URL.substring(0, SYSTEM_BASE_URL.length() - 1) : SYSTEM_BASE_URL;
        }

        String forwardedHost = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
        String host = (forwardedHost != null && !forwardedHost.isBlank()) ? forwardedHost : exchange.getRequestHeaders().getFirst("Host");

        if (host != null && !host.isBlank()) {
            String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
            String scheme = (proto != null && !proto.isBlank()) ? proto : "http";

            // If accessed via localhost, and LAN IP is available, fallback to LAN IP for local testing
            if ((host.startsWith("localhost") || host.startsWith("127.0.0.1")) && lanIp != null && !lanIp.equals("localhost")) {
                int colonIdx = host.indexOf(':');
                String portStr = (colonIdx != -1) ? host.substring(colonIdx) : ":" + PORT;
                return "http://" + lanIp + portStr;
            }

            return scheme + "://" + host;
        }
        return (lanIp != null && !lanIp.equals("localhost")) ? "http://" + lanIp + ":" + PORT : "http://localhost:" + PORT;
    }

    private static boolean isValidUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception e) {
            return false;
        }
    }

    private static String extract(Pattern pattern, String body) {
        Matcher m = pattern.matcher(body);
        if (m.find()) {
            return m.group(1).replace("\\/", "/").trim();
        }
        return null;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        // Inject Base64 favicon directly into HTML head
        String htmlWithFavicon = INDEX_HTML.replace("FAVICON_DATA_URI_PLACEHOLDER", logoDataUri);
        byte[] bytes = htmlWithFavicon.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static final String INDEX_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
              <title>shortie</title>
              <link rel="icon" type="image/png" href="/logo.png?v=shortie99">
              <link rel="shortcut icon" href="/logo.png?v=shortie99">
              <link rel="apple-touch-icon" href="FAVICON_DATA_URI_PLACEHOLDER">
              <link rel="apple-touch-icon-precomposed" href="FAVICON_DATA_URI_PLACEHOLDER">
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
              <link href="https://fonts.googleapis.com/css2?family=Instrument+Sans:ital,wght@0,400..700;1,400..700&display=swap" rel="stylesheet">
              <link rel="stylesheet" href="/style.css">
              <script src="https://cdn.jsdelivr.net/npm/qrcode-generator@1.4.4/qrcode.min.js"></script>
            </head>
            <body>
              <canvas id="motionCanvas"></canvas>

              <!-- Full Screen Width Far Left Aligned Clean Text Header -->
              <nav class="nav-bar">
                <a href="/" class="logo-box">
                  <span class="logo-title">shortie</span>
                </a>
              </nav>

              <div class="container">
                <!-- Hero Section -->
                <header>
                  <h1>Shorten Links.</h1>
                  <p class="hero-subtitle">Micro-link engine with live stats & instant QR codes.</p>
                </header>

                <!-- Dribbble Stadium Pill Search Bar -->
                <div class="bento-card">
                  <form class="shorten-form" onsubmit="event.preventDefault(); submitShorten();">
                    <div class="input-box">
                      <input type="url" id="urlInput" class="url-input" placeholder="What link are you looking to shorten?" required autofocus autocomplete="off">
                    </div>

                    <button type="button" class="alias-trigger" onclick="toggleAlias()">
                      <span>Alias</span>
                      <svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"></path></svg>
                    </button>

                    <!-- Circular Action Button -->
                    <button type="submit" id="btnSubmit" class="btn-cta" title="Shorten Link">
                      <svg width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
                    </button>
                  </form>

                  <div class="alias-drawer" id="aliasDrawer">
                    <div class="alias-field">
                      <span class="alias-tag" id="domainPrefixTag">link/</span>
                      <input type="text" id="aliasInput" class="alias-input" placeholder="custom-alias" pattern="[a-zA-Z0-9_-]+" oninput="onAliasTyping()">
                      <span id="aliasStatusBadge" class="alias-status"></span>
                    </div>
                    <div id="aliasSuggestionBox" class="alias-suggestion"></div>
                  </div>
                </div>

                <!-- Result Banner -->
                <div class="result-box" id="resultBox">
                  <div class="result-flex">
                    <a href="#" id="resultLinkHref" target="_blank" class="result-url"></a>
                    <div class="action-btns">
                      <button class="btn-ui primary" onclick="copyResult()">Copy</button>
                      <button class="btn-ui" onclick="openQrModal(latestShortUrl)">QR</button>
                    </div>
                  </div>
                </div>

                <!-- Recent Links Section (Minimal & Clean) -->
                <div class="dashboard">
                  <div class="dash-header">
                    <h2 class="dash-title">Recent Links</h2>
                    <div class="search-wrapper">
                      <svg class="search-icon" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
                      <input type="text" id="searchInput" class="search-box" placeholder="Search links..." oninput="onSearch()">
                    </div>
                  </div>

                  <div class="links-grid" id="linksContainer"></div>
                </div>
              </div>

              <!-- QR Modal Overlay -->
              <div class="modal-overlay" id="qrModal" onclick="if(event.target===this) closeQrModal()">
                <div class="modal-content">
                  <button class="modal-close-btn" onclick="closeQrModal()">&times;</button>
                  <h3 style="font-size:1.15rem; font-weight:700; margin-bottom:4px; color:var(--text-main);">Link QR Code</h3>
                  <p id="qrTargetUrl" style="font-size:0.82rem; color:var(--text-muted); word-break:break-all; margin-bottom:16px;"></p>
                  <canvas id="qrCanvas" width="400" height="400" style="width:180px; height:180px; background:#fff; padding:10px; border-radius:14px; margin:0 auto 16px; display:block; border:1px solid rgba(140,120,180,0.1);"></canvas>
                  <button class="btn-ui primary" style="width:100%%; justify-content:center;" onclick="downloadQrCode()">Save Image</button>
                </div>
              </div>

              <!-- Toast Area -->
              <div class="toast-area" id="toastArea"></div>

              <!-- Motion Engine -->
              <script>
                let linkStore = [];
                let latestShortUrl = "";
                let checkTimer = null;

                document.addEventListener('DOMContentLoaded', () => {
                  fetchLinks();
                  initMotionGraphics();
                  document.getElementById('domainPrefixTag').textContent = location.host + '/';
                });

                function toggleAlias() {
                  const drawer = document.getElementById('aliasDrawer');
                  drawer.classList.toggle('open');
                }

                function onAliasTyping() {
                  clearTimeout(checkTimer);
                  const val = document.getElementById('aliasInput').value.trim();
                  const badge = document.getElementById('aliasStatusBadge');
                  const suggestBox = document.getElementById('aliasSuggestionBox');

                  if (!val) {
                    badge.textContent = '';
                    badge.className = 'alias-status';
                    suggestBox.innerHTML = '';
                    return;
                  }

                  checkTimer = setTimeout(async () => {
                    try {
                      const res = await fetch('/api/check-alias/' + encodeURIComponent(val));
                      if (res.ok) {
                        const data = await res.json();
                        if (data.available) {
                          badge.textContent = 'Available';
                          badge.className = 'alias-status available';
                          suggestBox.innerHTML = '';
                        } else {
                          badge.textContent = 'Taken';
                          badge.className = 'alias-status taken';
                          suggestBox.innerHTML = `Suggested: <button type="button" class="suggest-btn" onclick="useSuggestion('${data.suggestion}')">${data.suggestion}</button>`;
                        }
                      }
                    } catch(e) {}
                  }, 250);
                }

                function useSuggestion(sug) {
                  document.getElementById('aliasInput').value = sug;
                  onAliasTyping();
                }

                async function submitShorten() {
                  const urlInput = document.getElementById('urlInput');
                  const aliasInput = document.getElementById('aliasInput');
                  const btnSubmit = document.getElementById('btnSubmit');

                  const url = urlInput.value.trim();
                  const alias = aliasInput.value.trim();
                  if (!url) return;

                  btnSubmit.disabled = true;

                  try {
                    const res = await fetch('/api/shorten', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ url, alias: alias || undefined })
                    });
                    const data = await res.json();

                    if (res.ok) {
                      latestShortUrl = data.shortUrl;
                      document.getElementById('resultLinkHref').href = data.shortUrl;
                      document.getElementById('resultLinkHref').textContent = data.shortUrl;
                      document.getElementById('resultBox').classList.add('active');

                      urlInput.value = '';
                      aliasInput.value = '';
                      document.getElementById('aliasStatusBadge').textContent = '';
                      document.getElementById('aliasSuggestionBox').innerHTML = '';
                      notify(data.message || 'Short link generated');
                      fetchLinks();
                    } else if (res.status === 409) {
                      notify('Alias is taken! Try another one.');
                      if (data.suggestion) {
                        document.getElementById('aliasSuggestionBox').innerHTML = `Alias taken. Try: <button type="button" class="suggest-btn" onclick="useSuggestion('${data.suggestion}')">${data.suggestion}</button>`;
                      }
                    } else {
                      notify('Error: ' + (data.error || 'Could not shorten link'));
                    }
                  } catch (err) {
                    notify('Server request failed');
                  } finally {
                    btnSubmit.disabled = false;
                  }
                }

                async function fetchLinks() {
                  try {
                    const res = await fetch('/api/links');
                    if (res.ok) {
                      linkStore = await res.json();
                      renderDashboard(linkStore);
                    }
                  } catch(e) {}
                }

                function renderDashboard(items) {
                  const container = document.getElementById('linksContainer');

                  if (items.length === 0) {
                    container.innerHTML = `<div class="empty-box">No micro links generated yet.</div>`;
                    return;
                  }

                  container.innerHTML = items.map(item => `
                    <div class="link-item">
                      <div class="link-info">
                        <div class="link-header">
                          <a href="${item.shortUrl}" target="_blank" class="link-target-short">${item.shortUrl}</a>
                          <span class="click-badge">${item.clicks} clicks</span>
                        </div>
                        <div class="link-target-orig" title="${cleanHtml(item.originalUrl)}">${cleanHtml(item.originalUrl)}</div>
                      </div>
                      <div class="link-actions">
                        <button class="icon-btn" title="Copy" onclick="copyText('${item.shortUrl}')">
                          <svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"></path></svg>
                        </button>
                        <button class="icon-btn" title="QR Code" onclick="openQrModal('${item.shortUrl}')">
                          <svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>
                        </button>
                        <button class="icon-btn danger" title="Delete" onclick="removeLink('${item.code}')">
                          <svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"></path></svg>
                        </button>
                      </div>
                    </div>
                  `).join('');
                }

                function onSearch() {
                  const q = document.getElementById('searchInput').value.toLowerCase();
                  const filtered = linkStore.filter(i =>
                    i.shortUrl.toLowerCase().includes(q) || i.originalUrl.toLowerCase().includes(q) || i.code.toLowerCase().includes(q)
                  );
                  renderDashboard(filtered);
                }

                async function removeLink(code) {
                  if (!confirm("Delete short link?")) return;
                  try {
                    const res = await fetch('/api/links/' + code, { method: 'DELETE' });
                    if (res.ok) {
                      notify("Link deleted");
                      fetchLinks();
                    }
                  } catch(e) {}
                }

                function copyText(str) {
                  if (navigator.clipboard && window.isSecureContext) {
                    navigator.clipboard.writeText(str)
                      .then(() => notify("Copied to clipboard"))
                      .catch(() => fallbackCopy(str));
                  } else {
                    fallbackCopy(str);
                  }
                }

                function fallbackCopy(str) {
                  const textArea = document.createElement("textarea");
                  textArea.value = str;
                  textArea.style.position = "fixed";
                  textArea.style.top = "0";
                  textArea.style.left = "0";
                  textArea.style.opacity = "0";
                  document.body.appendChild(textArea);
                  textArea.focus();
                  textArea.select();
                  try {
                    const successful = document.execCommand('copy');
                    if (successful) {
                      notify("Copied to clipboard");
                    } else {
                      notify("Copy failed, please copy manually");
                    }
                  } catch (err) {
                    notify("Copy failed, please copy manually");
                  }
                  document.body.removeChild(textArea);
                }

                function copyResult() {
                  if (latestShortUrl) copyText(latestShortUrl);
                }

                function notify(msg) {
                  const area = document.getElementById('toastArea');
                  const toast = document.createElement('div');
                  toast.className = 'toast-msg';
                  toast.textContent = msg;
                  area.appendChild(toast);
                  setTimeout(() => toast.remove(), 2500);
                }

                function cleanHtml(s) {
                  return String(s)
                    .replace(/&/g,'&amp;')
                    .replace(/</g,'&lt;')
                    .replace(/>/g,'&gt;')
                    .replace(/"/g,'&quot;')
                    .replace(/'/g,'&#39;');
                }

                function openQrModal(url) {
                  document.getElementById('qrTargetUrl').textContent = url;
                  document.getElementById('qrModal').classList.add('active');
                  drawQr(url);
                }
                function closeQrModal() {
                  document.getElementById('qrModal').classList.remove('active');
                }

                function drawQr(str) {
                  const canvas = document.getElementById('qrCanvas');
                  const ctx = canvas.getContext('2d');
                  const dim = canvas.width;
                  ctx.fillStyle = '#FFFFFF';
                  ctx.fillRect(0, 0, dim, dim);

                  try {
                    const qr = qrcode(0, 'M');
                    qr.addData(str);
                    qr.make();

                    const count = qr.getModuleCount();
                    const quietZone = 4;
                    const total = count + quietZone * 2;
                    const cell = dim / total;
                    const cellSize = Math.ceil(cell);

                    ctx.fillStyle = '#1e1926';
                    for (let r = 0; r < count; r++) {
                      for (let c = 0; c < count; c++) {
                        if (qr.isDark(r, c)) {
                          ctx.fillRect(
                            Math.round((quietZone + c) * cell),
                            Math.round((quietZone + r) * cell),
                            cellSize,
                            cellSize
                          );
                        }
                      }
                    }
                  } catch (e) {
                    ctx.fillStyle = '#1e1926';
                    ctx.font = '11px sans-serif';
                    ctx.textAlign = 'center';
                    ctx.fillText('QR generation failed', dim / 2, dim / 2);
                  }
                }

                function downloadQrCode() {
                  const canvas = document.getElementById('qrCanvas');
                  const a = document.createElement('a');
                  a.download = 'shortie-qr.png';
                  a.href = canvas.toDataURL('image/png');
                  a.click();
                }

                function initMotionGraphics() {
                  const canvas = document.getElementById('motionCanvas');
                  const ctx = canvas.getContext('2d');
                  let width, height;

                  function resize() {
                    width = canvas.width = window.innerWidth;
                    height = canvas.height = window.innerHeight;
                  }

                  window.addEventListener('resize', resize);
                  resize();

                  let step = 0;
                  function animate() {
                    ctx.clearRect(0, 0, width, height);

                    step += 0.005;

                    const cx1 = width * 0.3 + Math.sin(step) * 90;
                    const cy1 = height * 0.25 + Math.cos(step * 0.7) * 80;
                    const r1 = Math.min(width, height) * 0.45;

                    const grad1 = ctx.createRadialGradient(cx1, cy1, 0, cx1, cy1, r1);
                    grad1.addColorStop(0, 'rgba(175, 150, 245, 0.16)');
                    grad1.addColorStop(1, 'transparent');

                    ctx.globalAlpha = 0.9;
                    ctx.fillStyle = grad1;
                    ctx.beginPath();
                    ctx.arc(cx1, cy1, r1, 0, Math.PI * 2);
                    ctx.fill();

                    const cx2 = width * 0.7 + Math.cos(step * 0.8) * 100;
                    const cy2 = height * 0.5 + Math.sin(step * 0.6) * 90;
                    const r2 = Math.min(width, height) * 0.5;

                    const grad2 = ctx.createRadialGradient(cx2, cy2, 0, cx2, cy2, r2);
                    grad2.addColorStop(0, 'rgba(205, 180, 255, 0.14)');
                    grad2.addColorStop(1, 'transparent');

                    ctx.fillStyle = grad2;
                    ctx.beginPath();
                    ctx.arc(cx2, cy2, r2, 0, Math.PI * 2);
                    ctx.fill();

                    ctx.globalAlpha = 1;
                    requestAnimationFrame(animate);
                  }

                  animate();
                }
              </script>
            </body>
            </html>
            """;
}
