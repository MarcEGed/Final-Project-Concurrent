# THREADCONV — Setup Guide

Everything you need to get the project running on a fresh machine.

---

## What You Need to Install

### 1. Java 21 or newer (required)

The backend is written in Java. Any JDK version 21+ works (21, 22, 23…).

**Windows — download from:**
https://adoptium.net/temurin/releases/?version=21

Pick **JDK 21**, Windows x64, `.msi` installer. Run it, follow the prompts.

Verify:
```
java -version
```
Should print something like `openjdk version "21.x.x"` (or 22/23, that's fine too).

---

### 2. Gradle 8.x (build tool)

**Option A — download manually (no admin required):**
```powershell
curl -L https://services.gradle.org/distributions/gradle-8.10.2-bin.zip -o gradle.zip
Expand-Archive gradle.zip -DestinationPath "$HOME\gradle-8.10.2"
```
Then add `%USERPROFILE%\gradle-8.10.2\gradle-8.10.2\bin` to your PATH, or just prefix every gradle command with the full path:
```
$HOME\gradle-8.10.2\gradle-8.10.2\bin\gradle <command>
```

**Option B — use the Gradle wrapper (easiest, no install needed):**
```
cd code\converter\backend-java
.\gradlew <command>       # Windows
./gradlew <command>       # Mac/Linux
```
The wrapper (`gradlew`) downloads the right Gradle version automatically on first run.

---

### 3. Node.js 18+ (for the React frontend)

**Download from:** https://nodejs.org  
Pick the **LTS** version.

Verify:
```
node -v
npm -v
```

---

### 4. FFmpeg (for image and video conversion)

**Windows — easiest path:**
1. Download a build from https://www.gyan.dev/ffmpeg/builds/ (click "ffmpeg-release-essentials.zip")
2. Extract it somewhere like `C:\ffmpeg`
3. Add `C:\ffmpeg\bin` to your system PATH

**Or install via Chocolatey (admin PowerShell):**
```powershell
choco install ffmpeg
```

**Mac:**
```
brew install ffmpeg
```

**Linux:**
```
sudo apt install ffmpeg
```

Verify:
```
ffmpeg -version
```

---

## Project Structure

```
Final-Project-Concurrent-master/
├── code/
│   ├── converter/
│   │   ├── backend-java/       ← Java Spring Boot backend (THE active backend)
│   │   ├── backend/            ← OLD Node.js backend (ignore this)
│   │   └── frontend/           ← React frontend (unchanged, still works)
│   └── stress-advanced.js      ← Stress test script
├── SETUP.md                    ← This file
├── ARCHITECTURE.md             ← How it all works
└── STRESS-TESTING.md           ← How to run the stress tests
```

---

## Building the Backend

```powershell
cd code\converter\backend-java

# Option A — using the wrapper (recommended, no Gradle install needed)
.\gradlew bootJar --no-daemon

# Option B — if you installed Gradle and it's on your PATH
gradle bootJar --no-daemon
```

This produces: `build\libs\threadconv.jar`

---

## Running Everything

You need **three terminals** running at the same time.

### Terminal 1 — Java Backend

> **Windows gotcha:** If you have Oracle Java 8 installed, the plain `java` command will use it instead of your JDK 21/23 and you'll get an `UnsupportedClassVersionError`. Use the full path to your JDK instead. Find it with `where.exe java` — pick the path that is NOT inside `Common Files\Oracle`.

```powershell
cd code\converter\backend-java

# If java 21/23 is on PATH correctly:
java -jar build\libs\threadconv.jar

# If you hit a Java version error, use the full path (example for JDK 23):
"C:\Program Files\jdk-23.0.2\bin\java.exe" -jar build\libs\threadconv.jar
```

You should see Spring Boot start up and end with a line like:
```
Started ThreadConvApplication in 2.3 seconds
[WorkerPool] 2 core / 7 max workers, queue cap 500
```

Backend listens on:
- **Port 3001** — REST API (`/api/upload`, `/api/jobs`, etc.)
- **Port 3002** — Socket.IO (live progress updates)

### Terminal 2 — React Frontend

```powershell
cd code\converter\frontend
npm install          # only needed the first time
npm start
```

Opens at **http://localhost:3000**

### Terminal 3 — Stress Test (optional)

```powershell
cd code
node stress-advanced.js
```

---

## Stopping the Backend

In the terminal running the backend, press `Ctrl+C`.  
If it refuses to die (port still in use error on restart):

**Windows:**
```powershell
taskkill /F /IM java.exe
```

**Mac/Linux:**
```bash
pkill -f threadconv.jar
```

---

## Common Problems

| Problem | Fix |
|---|---|
| `java: command not found` | JDK not installed or not on PATH — run `where.exe java` |
| `UnsupportedClassVersionError` | Java 8 is on PATH instead of Java 21/23 — use full path (see above) |
| `Port 3001 already in use` | Old backend still running — kill it (see above) |
| `Port 3002 already in use` | Same — kill old java process |
| `ffmpeg: command not found` | FFmpeg not on PATH — add its `bin` folder to PATH |
| `ffmpeg exited with code 1` on video | Very old FFmpeg (pre-2016) — upgrade to a recent build |
| Frontend shows nothing / blank | Backend not running — start it first |
| `npm: command not found` | Node.js not installed |
