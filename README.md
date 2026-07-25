# shortie — Micro Link Engine

A ultra-fast, dependency-free URL shortener built with pure Java (JDK's high-performance `HttpServer`) and a sleek modern web interface. Zero external libraries required — compiles and runs out of the box.

---

## ✨ Features

- **⚡ Zero External Dependencies**: Built entirely with standard Java JDK (`com.sun.net.httpserver.HttpServer`).
- **📱 Cross-Device & Mobile Ready**: Auto-detects local LAN IP so generated links work seamlessly across phones, laptops, and tablets on the same Wi-Fi.
- **🏷️ Custom Aliases with Live Availability**: Real-time alias validation with instant fallback suggestions if an alias is taken.
- **📊 Real-time Dashboard & Search**: Instant click analytics, live link filter search, and link management.
- **📷 Instant QR Code Generator**: Generates clean QR codes for any short link with downloadable PNG images.
- **💾 Auto-Persistence**: Thread-safe in-memory `ConcurrentHashMap` with atomic file flushing to `urlshortener-data.txt`.
- **🎨 Premium UI Aesthetics**: Matte glassmorphism, fluid interactive animations, custom Apple Touch tab icons, and responsive Dribbble-inspired UI.

---

## 🚀 Quick Start

### 1. Requirements
- JDK 17+ (Tested on JDK 21)

### 2. Compile
```bash
mkdir -p out
javac -d out src/com/urlshortener/*.java
```

### 3. Run
```bash
java -cp out com.urlshortener.Main
```

By default, **shortie** listens on port `8080` and auto-prints LAN access URLs:
```text
=================================================
Shortie Engine Ready & Running!
  • Laptop Local: http://localhost:8080
  • Mobile Wi-Fi: http://172.20.10.3:8080
=================================================
```

### Configuration Options
Pass system properties to customize port or data storage location:
```bash
java -Dport=9000 -DdataFile=/data/links.txt -cp out com.urlshortener.Main
```

---

## 📡 REST API Reference

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `POST /api/shorten` | `POST` | Shorten a URL (supports optional custom `alias`). |
| `GET /api/check-alias/{alias}` | `GET` | Check if a custom alias is available or get a suggestion. |
| `GET /api/links` | `GET` | List all shortened links with click counts & timestamps. |
| `DELETE /api/links/{code}` | `DELETE` | Delete a shortened link by code/alias. |
| `GET /api/stats/{code}` | `GET` | Retrieve click statistics for a specific short link. |
| `GET /{code}` | `GET` | 302 Redirect to original target URL & increment click count. |

### Example Request: Shorten URL
```bash
curl -X POST http://localhost:8080/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/long/path", "alias": "my-alias"}'
```

### Example Response
```json
{
  "code": "my-alias",
  "shortUrl": "http://172.20.10.3:8080/my-alias",
  "originalUrl": "https://example.com/long/path",
  "createdAt": "2026-07-25T11:25:00Z",
  "message": "Short link generated"
}
```

---

## 🐳 Docker Support

Run with Docker in containerized environments:

```bash
# Build Docker image
docker build -t shortie .

# Run Docker container
docker run -p 8080:8080 -v $(pwd)/data:/app/data shortie
```

---

## 🛠️ Architecture Notes

- **Base62 Encoding**: Automatic short codes use sequential IDs converted into Base62 (`0-9a-zA-Z`) for minimal URL length.
- **Thread Safety**: Multithreaded execution handled via fixed thread pool executor (`Executors.newFixedThreadPool(16)`), `ConcurrentHashMap`, and `AtomicLong` for atomic click counters.
- **Zero-Downtime Flush**: Shutdown hook preserves data integrity on exit, restoring all entries upon restart.
