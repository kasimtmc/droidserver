# How to use

This guide is for people setting up DroidServer for the first time: it covers the basic steps from installing the app
to opening your server to the internet. The detailed technical documentation is on the
[project page](https://github.com/kasimtmc/droidserver) on GitHub; the **Help** icon in the app opens the same documents
offline.

## 1. Installing

DroidServer runs on Android 8.0 and later; no root is needed.

- **Google Play:** search for DroidServer on the Play Store and install it. You can try it free for a week from the
  first launch, then continue with a one-time purchase.
- **GitHub:** download `droidserver-<version>.apk` from the [Releases](https://github.com/kasimtmc/droidserver/releases)
  page and install it. The app finds new versions by itself (Settings → Update).

So that the server keeps running with the screen off, keep the phone on the charger and on Wi-Fi if you can.

## 2. First launch

After a short introduction comes the **How would you like to start?** page:

- **A ready-made example:** Blog, Shop and orders, Appointment or Task tracking. Collections, access rules, sample
  records and a simple site are set up for you. This is the quickest way to try it out.
- **Start empty (wizard):** you choose everything step by step.

![Welcome screen](../img/01-karsilama.png)

## 3. The wizard

The wizard asks what your server should do in seven steps. Fill in only what you need at each step; you can change
everything later with **Server → Edit with wizard**.

1. **General:** the server's name, port (8080 by default), API path and, if you want, a static site.
2. **Database:** SQLite (the default, nothing to install) or PostgreSQL; scheduled backups and cloud backup.
3. **Users:** email and password accounts for your app's users (optional).
4. **Collections:** the tables and fields of your data; REST and GraphQL endpoints are created for each collection
   automatically. This is where you choose who can read and write.
5. **Security:** API key or JWT, the web admin panel, rate limits and the firewall.
6. **Access:** where the server can be reached from (see "Access from the internet" below).
7. **Summary and deploy:** a summary of the changes and the schema plan. **Save and deploy** starts the server with the
   new settings.

![Wizard](../img/09-sihirbaz.png)

## 4. Starting the server

Tap **Start** on the **Status** screen. Once the server is running its address appears (for example
`http://192.168.1.20:8080`). You can open this address from a computer or phone on the same Wi-Fi.

The Status screen summarizes the last hour's requests and errors, the battery, running processes and alerts. If
something isn't working, the **Troubleshooter** checks connectivity, access and permissions on one screen and tells you
what to do about each finding.

![Status screen](../img/02-durum.png)

## 5. Connecting your app

Your app or script connects to the server with an API key:

- **Status → Client access / token** copies the key to the clipboard.
- **Status → Connect (QR)** shows the address and access details as a QR code.

For example, if you have a collection called `todos`, you read its records like this:

```
curl -H "Authorization: Bearer <key>" http://192.168.1.20:8080/api/collections/todos/records
```

To add a record, send JSON to the same address with `POST`. The server describes all its endpoints at
`/api/_openapi.json`; you can also try requests from inside the app with **Server → API explorer**.

## 6. Publishing a site

Put your site's files (`index.html`, CSS, JavaScript, images) in a zip file and pick it with **Server → Static site →
Upload zip**. Once the upload finishes the site is live; there's no need to restart the server. For no-code list and
detail pages use **Server → Pages**, and for dashboards with numbers and charts **Server → Dashboards**.

## 7. The web panel

To manage records, users, logs and settings from a computer's browser:

1. Turn on **Wizard → Security → Web admin panel** and save.
2. Open `http://<phone-address>:8080/_admin` in the browser.
3. On the phone, tap **Status → Web panel → Create pairing code** and enter the code shown in the panel.

If the panel can also be reached from the internet (a tunnel or a domain name), turn on the authenticator code in the
same wizard step too.

## 8. Access from the internet

At first the server can only be reached from your home network. In **Wizard → Access**:

- **Cloudflare Tunnel:** the easiest; works on mobile data too, no ports to open on the router. A quick tunnel without
  an account, or a permanent address with your own domain name.
- **Tailscale (only your own devices):** the server isn't exposed to the internet; only your own devices signed in to
  Tailscale can reach it.
- **Directly from the internet (IP address):** HTTPS on the phone's internet address; on Wi-Fi a port is opened on the
  router.

Before opening the server to the internet, review your collections' access rules and your API key.

## 9. Components

From the **Components** tab you can install side services such as PostgreSQL, Redis and Cloudflare Tunnel, and runtimes
for your own Node.js, Python or Java apps. **Install and run** downloads the component (from Google Play in the Play
version) and manages it together with the server.

## 10. Backups

**Server → Backups → Back up now** writes the database, uploaded files, scripts and the site into a single zip. In the
wizard's Database step you can turn on scheduled backups and encrypted cloud backup; the **Automatic copy folder**
also writes a copy of each backup to a folder you choose (for example Google Drive).

For security, secrets (passwords, API keys, the tunnel token) aren't included in backups. If you're moving a backup to
another phone, also keep:

- If you have encrypted fields, **Wizard → Database → Field encryption → Copy key**; on the new phone **Enter an
  existing key**.
- **Server → Environment and secrets (.env) → Export**; on the new phone **Import**.
- Re-enter the tunnel, Telegram and AI keys on the new phone.

## 11. Running in the background

Android may stop background apps to save battery. To keep the server running without interruption:

- Exempt DroidServer in **Settings → Battery optimization**.
- On phones such as Xiaomi, Huawei and Oppo, turn on **Autostart** in the app's settings and set its battery setting to
  "No restrictions".
- Turn on **Settings → Start when the device boots** so the server comes back after the phone restarts.

The **Troubleshooter** points out missing Android settings such as battery optimization; check the manufacturer's
autostart setting yourself.

## 12. Alerts and remote management

**Wizard → General → Health alerts**: you get a phone notification, an email or a Telegram message when the disk is
nearly full, errors increase, the battery gets hot or a backup fails. If you set up an admin chat on Telegram, you can
manage the server remotely with the bot's `/durum`, `/yedek`, `/loglar`, `/baslat` and `/durdur` commands.

## Help and support

See the [FAQ](faq.html) for common questions. To report a problem,
[open an issue](https://github.com/kasimtmc/droidserver/issues) on GitHub; if you can, attach a screenshot of the
**Logs** screen.
