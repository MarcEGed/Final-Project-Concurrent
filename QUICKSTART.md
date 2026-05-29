# QUICKSTART

Get the project running in three steps. No hardcoded paths — works wherever you cloned the repo.

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

## Step 1 — Start the Backend

Open a terminal in the project root (where this file lives), then:

**Windows:**
```
run-backend.bat
```

**Mac / Linux:**
```
chmod +x run-backend.sh
./run-backend.sh
```

The script will:
- Check your Java version
- Build the JAR automatically on the first run (takes ~1-2 min)
- Start the backend on ports 3001 and 3002

You should see:
```
[THREADCONV] Starting backend...
  REST API  >  http://localhost:3001
  Socket.IO >  http://localhost:3002
```

**Leave this terminal open.**

---

## Step 2 — Start the Frontend

Open a **second terminal** in the project root:

**Windows:**
```
run-frontend.bat
```

**Mac / Linux:**
```
./run-frontend.sh
```

Opens at **http://localhost:3000**

---

## Step 3 — Run the Stress Test (optional)

Open a **third terminal**:

```
cd code
node stress-advanced.js
```

---

## Java Version Problems

If the backend script says `Java 8 detected` or `Java X detected` but you have a newer JDK installed, it means an older Java is first on your PATH. Fix it by setting `JAVA_HOME` before running:

**Windows CMD:**
```
set JAVA_HOME=C:\path\to\your\jdk-21
run-backend.bat
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
| 3001 | Backend REST API |
| 3002 | Backend Socket.IO (live updates) |

---

## Stopping

Press **Ctrl+C** in each terminal.

If the backend port is still in use after stopping:

**Windows:** `taskkill /F /IM java.exe`  
**Mac/Linux:** `pkill -f threadconv.jar`

---

## Rebuilding After Code Changes

The scripts only build once (when `build/libs/threadconv.jar` is missing). To force a rebuild:

**Windows:**
```
cd code\converter\backend-java
gradlew.bat bootJar --no-daemon
```

**Mac / Linux:**
```
cd code/converter/backend-java
./gradlew bootJar --no-daemon
```

Then restart the backend.

---

For full architecture details see [ARCHITECTURE.md](ARCHITECTURE.md).  
For install troubleshooting see [SETUP.md](SETUP.md).
