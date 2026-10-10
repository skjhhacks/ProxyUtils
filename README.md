# Bed Wars Proxy

https://discord.gg/9NSBS5tWhj

A local Minecraft 1.8.x proxy for Hypixel Bed Wars. It runs on your PC. You add `localhost` as a
server in Minecraft, and it signs in to Hypixel as you and relays everything, adding alerts and
timers on the way. Nothing to install in the game: no mod, no Forge.

The features are ported from [OpenUtils](https://github.com/zhgmx/OpenUtils) (GPL-3.0, so this is too):

| Feature | What you get |
| --- | --- |
| Armor Alerts | Chat line + ping when an enemy buys chainmail / iron / diamond armor, with distance |
| Item Alerts | Chat line + ping when an enemy holds a sword, fireball, pearl, TNT, bow, potions, etc. |
| Upgrade Alerts | Chat line when an enemy team gets Sharpened Swords / Reinforced Armor |
| Timers HUD | Emerald and diamond spawn timers (and optional game time) |
| Upgrades HUD | Your team's trap queue, sharpness and protection |
| Quick Shop | Left click in the item shop is sent as a middle click |

## Run it

1. Install Java 8 or newer (`java -version` to check).
2. Double-click **`run.bat`** (or `java -jar dist/BedwarsProxy.jar`).
3. First run only: it prints a code and a link. Open the link, enter the code, sign in with the
   Microsoft account that owns Minecraft. The sign-in is saved in the `.bedwars-proxy` folder in
   your user folder (see "Several accounts" below).
4. Sign in with every account you play on, once: add them with `A` in the menu, `/bwp account new`
   in game, or `run.bat --new-login`.
5. In Minecraft **1.8.9** (any launcher: vanilla, Lunar, Prism, Badlion, ...): Multiplayer,
   Add Server, address `localhost`.
6. Join it. You land on Hypixel as the account that launcher is signed in to. Type `/bwp` for
   the in-game menu.

The proxy picks the account per connection, from the username your client reports. Log in as
Account A in one launcher and Account B in another, connect through `localhost` from both, and
each session plays as its own account. Clients that send no usable name (some bot tools) fall
back to the account chosen in the menu, which by default follows the vanilla launcher's active
account.

Keep the proxy window open while you play. It only listens on `127.0.0.1`, so nobody else on your
network can use it.

## Several accounts

The proxy remembers every Microsoft account you sign in with and plays as whichever one is selected.

| How | What it does |
| --- | --- |
| `run.bat` | One saved account: uses it. Several: uses the last one you played |
| `switch-account.bat` | Menu: pick a saved account, `A` add, `T` add with a pasted refresh token, `C` add from a cookie file, `R` remove, `F` toggle launcher-follow |
| the proxy's own window | Command prompt: `account <name>` to switch, `account token ...`, `account cookie ...`, `account list`, `stop` |
| `run.bat --account Bob` | Use a saved account by name or number without asking |
| `run.bat --new-login` | Add another account right now |
| `/bwp account` (in game) | Lists saved accounts |
| `/bwp account Bob` or `/bwp account 2` | Switch (overrides per-client routing until you reconnect from a client signed in as a saved account) |
| `/bwp account new` | Sign in with another account. The link and code appear in chat |
| `/bwp account token <token>` | Sign in with a pasted Microsoft refresh token |
| `/bwp account cookie <path>` | Sign in with a Netscape-format browser cookie file |
| `/bwp account remove Bob` | Forget a saved account |

**The proxy console is a command prompt too.** Every command below works in the black window the
proxy runs in, with or without the leading `/` or `/bwp`: `account`, `timers off`, `hud tablist`,
`set items.bow false`, `save`, ... so you can switch accounts without opening the game. Type `help`
there for the list, `stop` to close the proxy. When a console sign-in/switch succeeds you just
reconnect in Minecraft; no in-game step needed.

Tip: when signing in a new account, open the link in a private/incognito window so the browser
doesn't silently pick the account it already knows.

### Refresh tokens and cookie files

A **refresh token** is the long token the proxy itself saves after a sign-in; pasting one into
`T` (or `/bwp account token`) signs that account in without touching a browser. It looks like
`M.C551_...` or `1.MSA...`, one line, no quotes. Tokens are rotated on every use, so what the
proxy stores afterwards differs from what you pasted; that is normal.

A **cookie file** is a Netscape-format export of the *.login.live.com* cookies from a browser that
is signed into the Microsoft account (cookie-editor and similar extensions export this). `C` in the
menu (or `/bwp account cookie <path>`) replays the OAuth sign-in with those cookies, accepts the
consent page for you, and turns the account into a normal saved refresh-token account. If the
cookies are expired the error tells you to export a fresh file.

Only refresh tokens are stored, never passwords. Treat `accounts.json` (in the `.bedwars-proxy`
folder in your user folder) like a password file. To sign everything out, delete it.

## Where the timers and upgrades appear

A proxy cannot draw on your screen, so the HUD is injected into things Hypixel already shows.

| `hud.output` | Where | Notes |
| --- | --- | --- |
| `sidebar` (default) | Extra lines at the bottom of the scoreboard | See the limit below |
| `tablist` | Under the player list footer | Visible while holding Tab. Shows everything |
| `actionbar` | One line above the hotbar | Shows everything. Hypixel's own action bar messages briefly replace it |

Combine them with commas, e.g. `sidebar,tablist`. Change live with `/bwp hud tablist`.

**The sidebar is crowded.** Minecraft 1.8 draws at most 15 scoreboard lines and Hypixel's Bed Wars
sidebar already uses most of them (often 14). The proxy counts the free lines, packs the timers into
as few lines as possible (`E(2) 31 | D(3) 22`), and anything that does not fit is shown in the
action bar instead of being dropped. If you want everything in one place, use `actionbar` or
`tablist`. Numbers on the right of injected sidebar lines show as negative; that is cosmetic.

## In-game commands

```
/bwp                      status of every feature
/bwp armor off            toggle a feature: armor items upgradealerts timers upgradeshud quickshop
/bwp hud sidebar,tablist  where the HUD shows
/bwp set items.bow false  change any setting
/bwp save                 write settings to bedwars-proxy.properties
/bwp debug                print the parsed sidebar (use this if timers don't appear)
/bwp account              list / switch / add / remove Microsoft accounts
```

`/bwp` is handled by the proxy and never sent to the server. All settings (ping modes, which items
alert, which upgrades show) live in `bedwars-proxy.properties`, created on first run.

### Sounds

Every alert ping (armor, item and upgrade) goes through the master switch `alerts.sound` (default
`true`). Set it to `false` in `bedwars-proxy.properties` — or `/bwp set alerts.sound false` in game —
to mute all pings while the chat messages keep coming. The per-feature `armor.ping` / `items.ping`
modes still decide when a ping *would* happen; `alerts.sound=false` silences them all.

## Build and test

```
build.bat      # needs a JDK; produces dist\BedwarsProxy.jar
test.bat       # builds, then runs the offline test suite
```

The tests run a fake Hypixel and a fake client through the real proxy code, with compression on,
including a crowded 14-line sidebar, and check every feature's output and the account handling.
They do not touch the network or your accounts.

## What is and isn't verified

- Verified by the offline tests: login relay, compression, encryption, sidebar/team/tab parsing,
  all six features, the 15-line sidebar fitting, command handling, the Quick Shop packet rewrite,
  account storage / switching / menu, and Minecraft's server-hash against known vectors.
- Verified live: Microsoft's device-code endpoint accepts the client id used here. The Xbox side uses the user.authenticate endpoint with an `RpsTicket: t=` ticket (the Win32 SISU authorize and title.authenticate endpoints are dead for third-party clients - see the error table in the source), matching what the vanilla launcher sends. SignIn with a browser cookie file and with a pasted refresh token were also verified live end to end.
- **Not verified:** the full Microsoft/Xbox sign-in and a real Hypixel session. That needs your
  account, so the first run is the real test. If sign-in fails, the error text names the step.
  Run with `--debug` and keep the output.
- Sidebar line positions (used to detect "in a Bed Wars game" and the next-event line) are copied
  from OpenUtils. If Hypixel changes its scoreboard layout, `/bwp debug` shows what the proxy sees.

## Rules

Hypixel publishes a list of allowed mods; I could not find a statement about proxies specifically.
The alerts only use data the server already sends you. Quick Shop replaces one click packet with
another, one for one. Use your own judgement, and turn Quick Shop off (`/bwp quickshop off`) if you
want to stay strictly passive.

## Differences from the mod

- No movable HUD or settings screen (see above and `/bwp`).
- Quick Shop only rewrites a plain left click on a slot inside the shop window. Shift-clicks, clicks
  in your own inventory and number-key swaps are left alone: the mod cancels a click before the
  client acts on it, but a proxy sees it afterwards, so only the plain click can be undone cleanly.
- Font-based line cleanup is approximate (control characters and emoji are removed).
