<p align="center">
  <img src="logo.png" alt="BonoboSubs logo" width="128"/>
</p>

<h1 align="center">CloudStream-BonoboSubs</h1>

<p align="center">
  <a href="https://github.com/2Gbps/CloudStream-BonoboSubs/actions/workflows/build.yml"><img src="https://github.com/2Gbps/CloudStream-BonoboSubs/actions/workflows/build.yml/badge.svg" alt="Build"/></a>
  <img src="https://img.shields.io/badge/quality-4K%20%2B%201080p-brightgreen" alt="Quality"/>
  <img src="https://img.shields.io/badge/platform-CloudStream%20%7C%20AnymeX%20%7C%20ShonenX-blue" alt="Platform"/>
</p>

> Streams **Renegade Immortal (Xian Ni / 仙逆)** and its movie **Battle of the Gods** in 4K + 1080p HEVC with English subtitles, direct from the [BonoboSubs](https://buymeacoffee.com/bonobosubs) Nextcloud file share.

---

## Quick Install (Android)

Opens the app with the repository ready to add — works in **CloudStream**, **AnymeX**, and **ShonenX**:

<p align="center">
  <a href="https://2gbps.github.io/CloudStream-BonoboSubs/install.html"><b>Install BonoboSubs →</b></a>
</p>

## Manual Install

**CloudStream 3**
1. Open **Settings → Extensions**.
2. Tap **Add repository** and paste the import link:
```
https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/repo.json
```
3. Install the **BonoboSubs** plugin.

**AnymeX / ShonenX**
1. Open **Extensions → Manage extensions**.
2. Add the repository using the same import link above.
3. Install the **BonoboSubs** plugin.

## Content

| Title | Type | Quality | Source |
|---|---|---|---|
| Renegade Immortal | TV (episodes 1–157+) | 4K + 1080p | `bonobosubs.ovh/s/download?dir=/4k` |
| Renegade Immortal: Battle of the Gods | Movie | 4K + 1080p | `bonobosubs.ovh/s/download?dir=/4k` |

English subtitles included (embedded in the MKV and as external .srt for every episode).

## Build

```
.\gradlew.bat BonoboSubs:make
```

Outputs a `.cs3` plugin file into `BonoboSubs/build/`.

## Legal

This extension does not host any content. It fetches video files from a third-party file host the same way a web browser does. All content belongs to its respective owners — support BonoboSubs on [Buy Me a Coffee](https://buymeacoffee.com/bonobosubs).
