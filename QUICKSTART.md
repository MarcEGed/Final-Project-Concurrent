# QUICKSTART

Get the project running in four steps. No hardcoded paths — works wherever you cloned the repo.

---

## What You Need Installed

| Tool | Version | Link |
|---|---|---|
| Java JDK | **17 or newer** (21 recommended) | https://adoptium.net |
| Node.js | 18 or newer | https://nodejs.org |
| FFmpeg | Any recent build | See below |

### Installing FFmpeg

**Windows:** Download from https://www.gyan.dev/ffmpeg/builds/ (click `ffmpeg-release-essentials.zip`), extract, and add the `bin` folder to your PATH.

**Mac:**
```
brew install ffmpeg
```

**Linux:**
```
sudo apt install ffmpeg
```

Verify: `ffmpeg -version`

---

## Architecture Overview

The project runs as **two separate Java processes** that communicate over HTTP:

```
Browser → API Service (port 3001/3002) → Worker Service (port 3003)
                ↑                                  |
                └──────── HTTP callbacks ──────────┘
```

- **API Service** — receives uploads, manages jobs, pushes live updates via Socket.IO
- **Worker Service** — does the actual file conversion, reports progress back to the API

You must start both processes. Each runs in its own terminal.

---

## Step 1 — Start the API Service

Open a terminal in the project root (where this file lives):

**Windows:**
```
.\run-backend.bat
```

**Mac / Linux:**
```
chmod +x run-backend.sh
./run-backend.sh
```

You should see:
```
[THREADCONV] Starting backend...
  REST API  >  http://localhost:3001
  Socket.IO >  http://localhost:3002
```

**Leave this terminal open.**

---

## Step 2 — Start the Worker Service

Open a **second terminal** in the project root:

**Windows:**
```
.\run-worker.bat
```

**Mac / Linux:**
```
chmod +x run-worker.sh
./run-worker.sh
```

You should see:
```
[THREADCONV-WORKER] Starting worker service...
  Conversion Worker  >  http://localhost:3003
```

**Leave this terminal open.**

> The first time you run either service it will build the JAR automatically (~1-2 min).

---

## Step 3 — Start the Frontend

Open a **third terminal** in the project root:

**Windows:**
```
.\run-frontend.bat
```

**Mac / Linux:**
```
./run-frontend.sh
```

Opens at **http://localhost:3000**

---

## Step 4 — Run the Stress Test (optional)

Open a **fourth terminal**:

```
cd code
node stress-advanced.js
```

---

## Java Version Problems

If a service fails to start with a version error, set `JAVA_HOME` before running:

**Windows CMD:**
```
set JAVA_HOME=C:\path\to\your\jdk-21
.\run-backend.bat
```

**Windows PowerShell:**
```
$env:JAVA_HOME = "C:\path\to\your\jdk-21"
.\run-backend.bat
```

**Mac / Linux:**
```
export JAVA_HOME=/path/to/your/jdk-21
./run-backend.sh
```

To find where your JDK is:
- **Windows:** `where.exe java` — look for a path that is NOT in `Common Files\Oracle`
- **Mac:** `/usr/libexec/java_home -v 21`
- **Linux:** `update-alternatives --list java`

---

## Ports at a Glance

| Port | What |
|---|---|
| 3000 | React frontend |
| 3001 | API Service — REST endpoints |
| 3002 | API Service — Socket.IO live updates |
| 3003 | Worker Service — conversion engine |

---

## Stopping

Press **Ctrl+C** in each terminal.

If a port is still in use after stopping:

**Windows:** `taskkill /F /IM java.exe`  
**Mac/Linux:** `pkill -f threadconv`

---

## Rebuilding After Code Changes

The scripts only build once (when the JAR is missing). To force a rebuild:

**API service:**
```
cd code\converter\backend-java
gradlew.bat bootJar --no-daemon        # Windows
./gradlew bootJar --no-daemon          # Mac/Linux
```

**Worker service (run from backend-java where gradlew lives):**
```
cd code\converter\backend-java
gradlew.bat -p ..\backend-worker bootJar --no-daemon       # Windows
./gradlew -p ../backend-worker bootJar --no-daemon         # Mac/Linux
```

Then restart the relevant service.

---

For full architecture details see [ARCHITECTURE.md](ARCHITECTURE.md).  
For install troubleshooting see [SETUP.md](SETUP.md).
