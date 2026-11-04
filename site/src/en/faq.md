# Frequently asked questions

## General

### Does it need root?

No. DroidServer is a regular Android app; it runs on Android 8.0 and later.

### Where is my data?

On your phone. Records, files, user accounts, logs and backups stay on the phone; the developer collects no data. Data
only goes to the places you choose, through features you turn on (for example cloud backup or an AI provider). Details
are in the [privacy policy](privacy.html).

### Does it work without internet?

Yes. The server serves devices on the same Wi-Fi without an internet connection. For access from the internet you can
choose a Cloudflare tunnel, Tailscale, or direct access with a domain name.

### What happens if the phone turns off?

Your server and sites are reachable while the phone is on. If the phone turns off or loses its connection, they can't
be reached; with **Settings → Start when the device boots** on, the server starts by itself when the phone comes back on.

### The server stops in the background, what should I do?

Android and some manufacturers stop background apps to save battery. Exempt DroidServer from battery optimization; on
phones such as Xiaomi, Huawei and Oppo, turn on **Autostart** in the app's settings and set its battery setting to "No
restrictions". **Status → Troubleshooter** points out missing settings.

### What do you recommend for battery and heat?

Keep the phone on the charger; if your phone supports it, limit charging to 80% for battery health. Health alerts tell
you when the battery gets hot.

## Purchase and trial (Play version)

### How does the trial work?

The Google Play version can be tried free for 7 days from the first launch. The days left are shown in **Settings →
Google Play → Full version**; you can also buy there before the trial ends.

### What happens when the trial ends?

Your server, sites and automations keep running and your data isn't deleted. When you open the app a purchase screen
appears; once you buy the full version the app opens where you left off.

### Is it a subscription?

No, it's a one-time purchase. The price is shown on Google Play before you buy.

### I switched to a new phone, do I need to pay again?

No. The purchase is tied to your Google account: on a phone set up with the same account, tap **Restore purchase** on
the purchase screen.

### Can I get a refund?

Refunds follow Google Play's refund policy; you can request one through Google Play. A refunded purchase turns the full
version off.

### Does uninstalling and reinstalling renew the trial?

No. The moment the trial started is stored with your Google account.

## Versions

### What's the difference between the Google Play and GitHub versions?

Both run the same server. The Play version gets updates and components from Google Play and asks for a one-time
purchase after a one-week trial. The GitHub version is installed as an APK, downloads its updates and components from
GitHub and can send SMS directly from the SIM; it has no purchase or trial. The two can't be installed on the same phone
at the same time.

### Is the source code open?

Yes. DroidServer is published under GPL-3.0-or-later; the source code is
[on GitHub](https://github.com/kasimtmc/droidserver).

## Backups and moving

### How do I move my server to another phone?

On the old phone, take a backup with **Server → Backups → Back up now** and export it; on the new phone load it with
**Restore from file**. For security, secrets aren't included in backups: if you have encrypted fields, also move the
field encryption key and the **Environment and secrets (.env)** file, and re-enter the tunnel, Telegram and AI keys.
Details are in the [How to use](how-to-use.html) guide.

## Support

### I found a problem, how do I report it?

[Open an issue](https://github.com/kasimtmc/droidserver/issues) on GitHub. Include your phone model, the app version (in
Settings) and, if you can, a screenshot of the **Logs** screen.
