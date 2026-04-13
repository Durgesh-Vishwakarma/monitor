# Fixing Render Standby Times (Free Tier)

MicMonitor uses **Render.com's Free Web Service** tier for the backend server. Render has a strict policy for free-tier apps: **If your server does not receive any incoming traffic for 15 minutes, Render will put it to sleep.**

When the server is asleep:
1. The remote Android device gets disconnected from the WebSocket stream.
2. The Vercel Dashboard will display "Waking up server..." because the server physically takes **30 to 50 seconds** to boot back up when it receives the next request.
3. Live streaming and photo capture are delayed until the backend is fully awake.

## The Solution: Avoid Sleep Entirely

To get 24/7 reliability without ever waiting for the server to wake up, you need an external system to "ping" the backend regularly so it never hits the 15-minute inactivity mark. 

We recommend **UptimeRobot**, which is 100% free and easy to setup.

### Step 1: Sign up for UptimeRobot
Go to [UptimeRobot.com](https://uptimerobot.com/) and create a free account.

### Step 2: Add a New Monitor
1. Click **+ Add New Monitor** on your dashboard.
2. Select **Monitor Type**: `HTTP(s)`
3. Enter a **Friendly Name**: `MicMonitor Backend Ping`
4. Enter your **URL (or IP)**. Use your Render domain followed by `/health`
   > Example: `https://micmonitor-server-1234.onrender.com/health`
5. Set the **Monitoring Interval** to **14 minutes**.
   > [!IMPORTANT]
   > Do not set it to exactly 15 minutes or higher, because Render will already fall asleep. Set it to 10-14 minutes.
6. Click **Create Monitor**.

### Step 3: Verify it Works
You will immediately see the "Up" status on UptimeRobot. Your continuous pings will now trick Render into thinking the server is actively being used 24/7. 

> [!NOTE]
> Even with UptimeRobot keeping your server alive, Render tracks free usage hours. You get 750 free hours a month on Render, which is enough to keep exactly **1 free web service running 24/7** for the entire month!

## Alternative Options

- **Render Starter Plan**: If you upgrade to the $7/month Starter plan on Render, your web service will never spin down and you won't need UptimeRobot.
- **Vercel Cron Jobs**: Vercel offers cron jobs, but their Hobby tier only allows **1 run per day**, so it cannot be used to ping the server every 14 minutes.
