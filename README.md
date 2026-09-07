<p align="center">
  <img src="logo.png" alt="BonoboSubs logo" width="128"/>
</p>

<h1 align="center">CloudStream-BonoboSubs</h1>

<p align="center">
  <a href="https://github.com/2Gbps/CloudStream-BonoboSubs/actions/workflows/build.yml"><img src="https://github.com/2Gbps/CloudStream-BonoboSubs/actions/workflows/build.yml/badge.svg" alt="Build"/></a>
  <img src="https://img.shields.io/badge/quality-4K%-brightgreen" alt="Quality"/>
  <img src="https://img.shields.io/badge/platform-CloudStream%20%2F%-blue" alt="Platform"/>
</p>

> Streams **Renegade Immortal (Xian Ni / 仙逆)** and its movie **Battle of the Gods** in 4K HEVC, direct from the [BonoboSubs](https://buymeacoffee.com/bonobosubs) Nextcloud file share.

---

## Install — one tap (Android)

Open the install page on your phone and tap the button — it fires the `cloudstreamrepo://` deep link that both **ShonenX** and **CloudStream** register, so the app opens with the repository ready to add:

<p align="center">
  <a href="https://2gbps.github.io/CloudStream-BonoboSubs/install.html"><b>Install BonoboSubs →</b></a>
</p>

## Import link:

```
https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/repo.json
```

## Install

**CloudStream 3**
1. Open **Settings → Extensions**.
2. Tap **Add repository** and paste the import link above.
3. Install the **BonoboSubs** plugin.

**Direct install** (skip the repository):

```
https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/builds/BonoboSubs.cs3
```

## Content

| Title | Type | Source |
|---|---|---|
| Renegade Immortal | TV (episodes 1–157+) | `bonobosubs.ovh/s/download?dir=/4k` |
| Renegade Immortal: Battle of the Gods | Movie | `bonobosubs.ovh/s/download?dir=/4k` |


## Build

```
.\gradlew.bat BonoboSubs:make
```

Outputs a `.cs3` plugin file into `BonoboSubs/build/`.

## Legal

This extension does not host any content. It fetches video files from a third-party file host the same way a web browser does. All content belongs to its respective owners — support BonoboSubs on [Buy Me a Coffee](https://buymeacoffee.com/bonobosubs).
