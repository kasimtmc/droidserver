# Privacy policy

[Türkçe](PRIVACY.md) | **English**

DroidServer is a server that runs on your phone. The developer collects no data: the app has no analytics, ads, crash
report uploads or usage tracking. Your server's data (records, files, user accounts, logs, backups) stays on your phone;
only features you turn on send data, and only to the places you choose.

## Connections the app makes on its own

- **Update check (GitHub version):** once a day the release list on GitHub is checked (`api.github.com`). It can be
  turned off in Status → Settings → Update; when off, updates are only checked manually. In the Play version Google Play
  does the updating and there is no such check.
- **Component catalog:** in the GitHub version, when you open the Components screen, the list of side services and the
  package you pick are downloaded from GitHub. In the Play version the list ships with the app and the component you
  pick is downloaded from Google Play.
- **Maps:** when you show records with a location on a map (Map and dashboards in the app, the web panel), map tiles are
  loaded from OpenStreetMap (`tile.openstreetmap.org`). If a dashboard you share has a map, the browser of whoever opens
  it loads the tiles from there too.

These requests send no information about your phone or server (GitHub and OpenStreetMap see your IP address, as with
any web request; tile requests reveal the area being viewed).

## Features you turn on

The following only work once you configure them, and send data only to the service you configured: internet access
(Cloudflare, Tailscale, DuckDNS, Let's Encrypt), e-mail (your SMTP server), health alerts and commands (Telegram), cloud
backup (the S3/WebDAV storage you choose, end-to-end encrypted), web push (browsers' push services), social login
(Google, GitHub), AI (Claude, OpenAI, Gemini; images too if image tagging and OCR are on), webhooks and the live replica
(your own servers), SMS (see below), packages (the address or GitHub repository you give).

- **SMS:** there are three ways and you choose which are on. SMS provider (Netgsm, İleti Merkezi, Twilio): the
  recipient's number and the SMS text are sent to the provider. Directly from the SIM (GitHub version only): the SMS goes
  from your own line. From the SIM with your confirmation: a notification appears for each SMS; if you tap "Send SMS",
  your phone's SMS app opens with the number and text and you send it; in this mode the number and text are only shown
  in the notification and your SMS app and aren't sent anywhere else. In the audit log the last four digits of the
  number are hidden and the text isn't written.
- **On-device AI:** the model you choose is downloaded from Hugging Face (`huggingface.co`); it runs on the phone and your
  prompts and records are not sent anywhere.
- **Payments (Stripe, iyzico, Paddle):** the buyer enters card details on the provider's own page; card data never passes
  through the phone. The server sends the provider the amount, the description and (if you give it) the buyer's e-mail;
  iyzico also requires the buyer's name, ID number, phone, address and IP address. Paddle's checkout opens in the
  buyer's browser with Paddle's script (`cdn.paddle.com`). Payment notifications from the provider are written to the
  payment records on your phone.
- **Calendar and contacts (CalDAV/CardDAV):** records are synced only with the calendar and contacts apps you connect;
  they are not sent to any third party.

Secrets (API keys, passwords, tokens) are kept in the phone's encrypted store and are never written to the
configuration file or to backups.

## Permissions

| Permission | Why |
|---|---|
| Internet, network and Wi-Fi state | Receiving requests, showing the server's address, reconfiguring when the network changes |
| Wi-Fi multicast | Letting the server be found on the local network (mDNS, optional) |
| Foreground service, wake lock, battery optimization exemption | Keeping the server running while the screen is off |
| Start at boot | Starting the server after the phone restarts (optional) |
| Notifications | Server status, health alerts and updates |
| Biometrics | App lock (optional) |
| Send SMS (GitHub version only) | SMS login codes and SMS from scripts/automations, directly from the SIM (only if you turn it on; asked for then). The Play version doesn't have this permission |
| Location | Letting scripts and the API read the phone's last known location (only if you turn it on; asked for then). Background location isn't requested; in the Play version the location is only read while the app is on screen |
| Install packages (GitHub version only) | In-app updates (you confirm every installation) |

The Play version doesn't have the battery optimization exemption permission either; you grant the exemption in
Android's battery settings.

For questions and reports, open an issue on the project's GitHub page.
