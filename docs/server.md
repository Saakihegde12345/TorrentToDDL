# Headless backend

The same multi-torrent backend can run without a desktop. It provides an
authenticated administration API and HTTP file downloads. No hosted instance
is provisioned by this repository. A public deployment requires a machine,
domain/TLS configuration and persistent storage you control.

## Container

The Dockerfile builds a Linux x64 backend and runs it as UID 10001, with `/data`
as persistent storage. Prepare two independent random URL-safe keys (32–128
characters) in the `TTD_ADMIN_KEY` and `TTD_DOWNLOAD_KEY` environment variables.
They must differ; neither belongs in Git.

```sh
docker build -t torrenttoddl .
docker volume create torrenttoddl-data
docker run -d --name torrenttoddl --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -e TTD_ADMIN_KEY -e TTD_DOWNLOAD_KEY \
  -v torrenttoddl-data:/data torrenttoddl
```

Place an HTTPS reverse proxy in front of loopback port 8080 for internet use.
Configure it to stream responses without a short upstream read timeout, since
uncached torrent pieces can take up to two minutes to arrive. For a host bind
mount, grant UID 10001 write access to the chosen directory. Settings, queue
metadata, caches and exports survive container recreation through the volume.

The headless API is integration-tested on Windows. The Linux Docker image still
requires an actual Docker build/run on the deployment host.

## API

Administration requires `Authorization: Bearer ADMIN_KEY`. A shared download
key does not authorize API commands. POST commands use URL-encoded forms,
require Content-Length, and have a 64 KiB body limit. HTTP 202 acknowledges the
command; query status for resulting metadata/progress.

| Method | Route | Form fields |
| --- | --- | --- |
| GET | `/api/status` | None; returns torrent IDs, names, progress and file indices |
| POST | `/api/magnets` | `magnet` |
| POST | `/api/pause` | `id`, `paused=true` or `false` |
| POST | `/api/remove` | `id`; retains cache and saved files |
| POST | `/api/save` | `id`, `file`; saves to the server's configured download folder |

```sh
curl -H "Authorization: Bearer $TTD_ADMIN_KEY" https://your-domain/api/status
curl -H "Authorization: Bearer $TTD_ADMIN_KEY" \
  --data-urlencode "magnet=$MAGNET" https://your-domain/api/magnets
```

Download files directly without an export copy:

```text
https://your-domain/torrents/TORRENT_ID/files/FILE_INDEX/display-name?token=DOWNLOAD_KEY
```

The filename is display-only. Torrent/file identity and ETags control byte
selection and resumes. Requests without a valid download key fail with 401.

## Mobile background downloads

A remote backend keeps BitTorrent work off the phone and provides HTTPS URLs
that an operating-system HTTP download service can fetch. This is the supported
architecture for long mobile background downloads. It does not make an iOS 17
app's local C++ BitTorrent session exempt from suspension. The current native
iOS app stops its local gateway when backgrounded. Its separate Remote
Background Downloads panel uses a background URLSession for HTTPS gateway links,
persists progress/history and offers Save to Files when complete. The new iOS
client source and URL validation tests require the macOS/Xcode CI and device QA;
they have not been compiled on the Windows development machine.
