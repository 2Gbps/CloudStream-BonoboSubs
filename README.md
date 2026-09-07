<p align="center">
  <img src="logo.png" alt="BonoboSubs logo" width="128"/>
</p>

<h1 align="center">CloudStream-BonoboSubs</h1>

<p align="center">
  <a href="https://github.com/2Gbps/CloudStream-BonoboSubs/actions/workflows/build.yml"><img src="https://github.com/2Gbps/CloudStream-BonoboSubs/actions/workflows/build.yml/badge.svg" alt="Build"/></a>
  <img src="https://img.shields.io/badge/quality-4K%20HEVC-brightgreen" alt="Quality"/>
  <img src="https://img.shields.io/badge/platform-CloudStream%20%2F%20ShonenX-blue" alt="Platform"/>
</p>

> Streams **Renegade Immortal (Xian Ni / 仙逆)** and its movie **Battle of the Gods** in 4K HEVC, direct from the [BonoboSubs](https://buymeacoffee.com/bonobosubs) Nextcloud file share.

---

## Import link:

```
https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/repo.json
```

## Install

**ShonenX**
1. Open **Extensions → Add / Manage extensions**.
2. Tap **Add repository** and paste the import link above.
3. Install the **BonoboSubs** plugin.

**CloudStream 3**
1. Open **Settings → Extensions**.
2. Tap **Add repository** and paste the import link above.
3. Install the **BonoboSubs** plugin.

**Direct install** (skip the repository):

```
https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/builds/BonoboSubs.cs3
```

The plugin is rebuilt automatically on every push by GitHub Actions and published to the `builds` branch — you always get the latest build.

## Content

| Title | Type | Source |
|---|---|---|
| Renegade Immortal | TV (episodes 1–157+) | `bonobosubs.ovh/s/download?dir=/4k` |
| Renegade Immortal: Battle of the Gods | Movie | `bonobosubs.ovh/s/download?dir=/4k` |

## How it works

- Episodes are discovered **dynamically** through the Nextcloud WebDAV API (`PROPFIND` on the public share) — new releases published by BonoboSubs appear automatically, with no extension update needed.
- Both current file naming schemes are handled: `... Xian Ni - 001.mkv` (v3 batches) and `... Xian Ni Episode 077.mkv` (older releases).
- Files are streamed directly (`video/x-matroska`, range-request capable, ~1–2.5 GB per episode, HEVC 4K 2160p).
- English subtitles are embedded in the MKV container and picked up by the player.

## Build

```
.\gradlew.bat BonoboSubs:make
```

Outputs a `.cs3` plugin file into `BonoboSubs/build/`.

## Legal

This extension does not host any content. It fetches video files from a third-party file host the same way a web browser does. All content belongs to its respective owners — support BonoboSubs on [Buy Me a Coffee](https://buymeacoffee.com/bonobosubs).
