# CloudStream-BonoboSubs

CloudStream extension for [ShonenX](https://github.com/roshancodespace/ShonenX) (and CloudStream 3) that streams **Renegade Immortal (Xian Ni / 仙逆)** and its movie **Battle of the Gods** in 4K HEVC, direct from the [BonoboSubs](https://buymeacoffee.com/bonobosubs) Nextcloud file share.

## Install

Add the repository to your app's extension settings with this URL:

```
https://raw.githubusercontent.com/2Gbps/CloudStream-BonoboSubs/main/repo.json
```

- **CloudStream 3**: Settings → Extensions → Add repository → paste the URL.
- **ShonenX**: Extensions → Add / Manage extensions → paste the URL.

The plugin is built automatically on every push by GitHub Actions and published to the `builds` branch.

## Content

| Title | Type | Source |
|---|---|---|
| Renegade Immortal | TV (episodes 1–157+) | `bonobosubs.ovh/s/download?dir=/4k` |
| Renegade Immortal: Battle of the Gods | Movie | `bonobosubs.ovh/s/download?dir=/4k` |

Episodes are discovered **dynamically** through the Nextcloud WebDAV API (`PROPFIND` on the public share) — new releases published by BonoboSubs appear automatically without any extension update. Both current file naming schemes (`... Xian Ni - 001.mkv` and `... Xian Ni Episode 077.mkv`) are handled.

Files are streamed directly (`video/x-matroska`, range-request capable, ~1–2.5 GB per episode, HEVC 4K). English subtitles are embedded in the MKV container.

## Build

```
.\gradlew.bat BonoboSubs:make
```

Outputs a `.cs3` plugin file into `BonoboSubs/build/`.

## Legal

This extension does not host any content. It fetches video files from a third-party file host the same way a web browser does. All content belongs to its respective owners — support BonoboSubs on [Buy Me a Coffee](https://buymeacoffee.com/bonobosubs).
