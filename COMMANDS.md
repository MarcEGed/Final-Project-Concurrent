# THREADCONV — Commands Cheat Sheet

Quick reference. Copy-paste these exactly.

---

## IMPORTANT: Java Version Issue (Windows)

Your machine has **Java 8** installed that hijacks the `java` command.  
You must use the **full path** to Java 23 every time you run the backend.

```
"C:\Program Files\jdk-23.0.2\bin\java.exe"
```

If your Java 23 is installed somewhere else, find it with:
```powershell
where.exe java
```
Pick the path that says `jdk-23` (not the one in `Common Files\Oracle`).

---

## Step 1 — Build the Backend JAR (one-time, or after code changes)

Open a terminal (CMD or PowerShell), then:

```
cd "c:\Users\h\Downloads\Final-Project-Concurrent-master\Final-Project-Concurrent-master\code\converter\backend-java"
set JAVA_HOME=C:\Program Files\jdk-23.0.2
gradlew.bat bootJar --no-daemon
```

Wait for `BUILD SUCCESSFUL`. This creates `build\libs\threadconv.jar`.

> **Note:** `gradlew.bat` is the Gradle wrapper — it's already in the project folder, no separate Gradle install needed. The `set JAVA_HOME` line forces it to use Java 23 instead of any older Java on your machine.

---

## Step 2 — Run the Backend

```powershell
cd "c:\Users\h\Downloads\Final-Project-Concurrent-master\Final-Project-Concurrent-master\code\converter\backend-java"
"C:\Program Files\jdk-23.0.2\bin\java.exe" -jar build\libs\threadconv.jar
```

You should see:
```
[SocketService] Socket.IO listening on port 3002
[WorkerPool] 2 core / 7 max workers, queue cap 500
Started ThreadConvApplication in X seconds
```

**Leave this terminal open.** The backend runs until you press Ctrl+C.

---

## Step 3 — Run the Frontend (new terminal)

```powershell
cd "c:\Users\h\Downloads\Final-Project-Concurrent-master\Final-Project-Concurrent-master\code\converter\frontend"
npm install
npm start
```

`npm install` only needs to run the first time. After that just `npm start`.

Opens at: **http://localhost:3000**

---

## Step 4 — Run the Stress Test (new terminal, optional)

```powershell
cd "c:\Users\h\Downloads\Final-Project-Concurrent-master\Final-Project-Concurrent-master\code"
node stress-advanced.js
```

---

## Stopping / Restarting the Backend

Press **Ctrl+C** in the backend terminal.

If the port is still in use after stopping:

```powershell
taskkill /F /IM java.exe
```

---

## Check Backend is Running

```powershell
curl http://localhost:3001/api/stats
```

Should return a JSON response with `activeWorkers`, `totalCompleted`, etc.

---

## Summary of Ports

| Port | What |
|---|---|
| 3000 | React frontend |
| 3001 | Backend REST API |
| 3002 | Backend Socket.IO |

---

## If You Changed Java Source Code

Rebuild the JAR first, then restart the backend:

```
cd "c:\Users\h\Downloads\Final-Project-Concurrent-master\Final-Project-Concurrent-master\code\converter\backend-java"

rem 1. Kill old backend if running
taskkill /F /IM java.exe

rem 2. Rebuild
set JAVA_HOME=C:\Program Files\jdk-23.0.2
gradlew.bat bootJar --no-daemon

rem 3. Start new backend
"C:\Program Files\jdk-23.0.2\bin\java.exe" -jar build\libs\threadconv.jar
```
