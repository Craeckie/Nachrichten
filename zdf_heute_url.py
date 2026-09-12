#!/usr/bin/env python3
"""Extract the video URL for the first entry of a ZDF mediathek magazine page.

Default target is the "heute 19 Uhr" page:
    https://www.zdf.de/magazine/heute-19-uhr-102

How it works (plain HTTP, no headless browser needed):
  1. The page server-renders all episode data as escaped JSON in the HTML.
  2. The first ``contentType:"EPISODE"`` node is the most recent broadcast. It
     carries one or more media variants, each with a ``ptmdTemplate`` and a
     ``vodMediaType`` (DEFAULT = normal broadcast, DGS = sign language, ...).
  3. The short-lived player API token lives in the same HTML
     (``videoToken.apiToken``) and is scraped fresh on every run.
  4. The ptmd template resolves to the actual stream list:
        GET https://api.zdf.de/tmd/2/<playerId>/vod/ptmd/mediathek/<id>/<v>
        Header: Api-Auth: Bearer <apiToken>
     The response lists HLS (.m3u8), progressive MP4 and WebM URLs.

Stdlib only — no third-party dependencies.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

DEFAULT_PAGE = "https://www.zdf.de/magazine/heute-19-uhr-102"
API_BASE = "https://api.zdf.de"
DEFAULT_PLAYER_ID = "android_native_5"  # returns the widest set of formats
UA = (
    "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) "
    "Gecko/20100101 Firefox/128.0"
)

# progressive video quality, best first
QUALITY_ORDER = ["uhd", "fhd", "hd", "veryhigh", "high", "med", "low", "auto"]


class ExtractError(RuntimeError):
    """Raised for any recoverable failure with a user-facing message."""


def _http_get(url: str, headers: dict | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read()
    except urllib.error.HTTPError as exc:
        raise ExtractError(f"HTTP {exc.code} fetching {url}") from exc
    except urllib.error.URLError as exc:
        raise ExtractError(f"Network error fetching {url}: {exc.reason}") from exc


def fetch_page(url: str) -> str:
    return _http_get(url).decode("utf-8", errors="replace")


def extract_api_token(html: str) -> str:
    """Pull the videoToken.apiToken out of the embedded (escaped) JSON."""
    m = re.search(r'apiToken\\?":\\?"([A-Za-z0-9]+)', html)
    if not m:
        raise ExtractError(
            "Could not find apiToken in page (ZDF may have changed the layout). "
            f"Page length: {len(html)} bytes."
        )
    return m.group(1)


def find_first_episode(html: str, want_dgs: bool = False) -> dict:
    """Locate the first real (non-teaser) episode node and return its
    title/canonical/ptmd.

    Returns a dict: {title, canonical, sharing_url, ptmd_template, vod_media_type}
    """
    # Every node on the page (real episode or clip teaser) opens with this
    # header. There is no reliable single-node closing brace to bound the
    # search on anymore (see episode_marker_re below), so blocks are bounded
    # by "next header's start" instead.
    header_re = re.compile(
        r'\\"id\\":\\"([0-9a-f-]{36})\\"'
        r',\\"canonical\\":\\"([^\\]+)\\"'
        r',\\"title\\":\\"([^\\]+)\\"'
    )
    # A real broadcast (as opposed to a clip teaser) carries a populated
    # episodeInfo; teasers have "seasonNumber":null,"episodeNumber":null.
    # This marker can sit behind other closed sub-objects within the node
    # (there may be a "}" between the header and it), which is exactly why
    # the block can no longer be bounded by "next }".
    episode_marker_re = re.compile(
        r'\\"episodeInfo\\":\{\\"seasonNumber\\":\d+,\\"episodeNumber\\":\d+'
    )

    headers = list(header_re.finditer(html))
    if not headers:
        raise ExtractError("No id/canonical/title headers found on the page.")

    marker_hits = 0
    for i, h in enumerate(headers):
        block_end = headers[i + 1].start() if i + 1 < len(headers) else h.end() + 12000
        block = html[h.start():block_end]
        if not episode_marker_re.search(block):
            continue
        marker_hits += 1

        # sharingUrl, if present in the block
        sm = re.search(r'sharingUrl\\":\\"([^\\]+)', block)
        sharing_url = sm.group(1) if sm else None

        # Collect (vodMediaType, ptmdTemplate) pairs within this episode's block.
        variants: list[tuple[str, str]] = []
        for pm in re.finditer(r'ptmdTemplate\\":\\"([^\\]+)\\"', block):
            ctx = block[max(0, pm.start() - 160): pm.end() + 160]
            vt = re.search(r'vodMediaType\\":\\"([^\\]+)', ctx)
            variants.append((vt.group(1) if vt else "UNKNOWN", pm.group(1)))

        if not variants:
            # Not yet aired / no stream published yet for this broadcast —
            # move on to the next matching candidate rather than failing.
            continue

        wanted_type = "DGS" if want_dgs else "DEFAULT"
        chosen = next((v for v in variants if v[0] == wanted_type), None)
        if chosen is None:
            # Fall back to the first variant, but tell the user what we got.
            chosen = variants[0]
            sys.stderr.write(
                f"note: no {wanted_type} variant found; "
                f"using '{chosen[0]}' instead.\n"
            )

        return {
            "title": h.group(3),
            "canonical": h.group(2),
            "sharing_url": sharing_url,
            "ptmd_template": chosen[1],
            "vod_media_type": chosen[0],
        }

    raise ExtractError(
        "No playable episode found on the page "
        f"({len(headers)} node header(s) found, {marker_hits} matched the "
        "episode marker, none had a ptmdTemplate)."
    )


def fetch_ptmd(ptmd_template: str, token: str, player_id: str) -> dict:
    path = ptmd_template.replace("{playerId}", player_id)
    url = API_BASE + path
    raw = _http_get(url, headers={"Api-Auth": f"Bearer {token}"})
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ExtractError(f"ptmd response was not valid JSON: {exc}") from exc


def collect_streams(ptmd: dict) -> list[dict]:
    """Flatten the ptmd into a list of stream dicts (main German audio only)."""
    streams: list[dict] = []
    for pl in ptmd.get("priorityList", []):
        for fmt in pl.get("formitaeten", []):
            mime = fmt.get("mimeType")
            for q in fmt.get("qualities", []):
                quality = q.get("quality")
                for track in q.get("audio", {}).get("tracks", []):
                    streams.append(
                        {
                            "mimeType": mime,
                            "quality": quality,
                            "class": track.get("class"),
                            "language": track.get("language"),
                            "uri": track.get("uri"),
                        }
                    )
    # Keep only the main audio track in German (skip audio-description etc.)
    main = [s for s in streams if s["class"] == "main"
            and s["language"] in (None, "deu")]
    return main or streams


def _quality_rank(quality: str | None) -> int:
    try:
        return QUALITY_ORDER.index(quality)
    except ValueError:
        return len(QUALITY_ORDER)


def pick_best_progressive(streams: list[dict]) -> dict | None:
    prog = [s for s in streams if s["mimeType"] in ("video/mp4", "video/webm")]
    # prefer mp4 over webm, then best quality
    prog.sort(key=lambda s: (s["mimeType"] != "video/mp4", _quality_rank(s["quality"])))
    return prog[0] if prog else None


def pick_best_hls(streams: list[dict]) -> dict | None:
    hls = [s for s in streams if s["mimeType"] == "application/x-mpegURL"]
    # 'auto' master playlist is the full adaptive set; otherwise best quality
    hls.sort(key=lambda s: (s["quality"] != "auto", _quality_rank(s["quality"])))
    return hls[0] if hls else None


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("url", nargs="?", default=DEFAULT_PAGE,
                   help=f"ZDF magazine page URL (default: {DEFAULT_PAGE})")
    p.add_argument("--hls", action="store_true",
                   help="print the adaptive HLS (.m3u8) master URL instead of MP4")
    p.add_argument("--all", action="store_true",
                   help="print every available stream URL")
    p.add_argument("--json", action="store_true",
                   help="print structured JSON (metadata + all streams)")
    p.add_argument("--dgs", action="store_true",
                   help="use the sign-language (DGS) variant")
    p.add_argument("--player-id", default=DEFAULT_PLAYER_ID,
                   help=f"ZDF player id for ptmd (default: {DEFAULT_PLAYER_ID})")
    args = p.parse_args(argv)

    try:
        html = fetch_page(args.url)
        token = extract_api_token(html)
        episode = find_first_episode(html, want_dgs=args.dgs)
        ptmd = fetch_ptmd(episode["ptmd_template"], token, args.player_id)
        streams = collect_streams(ptmd)
        if not streams:
            raise ExtractError("ptmd contained no stream URLs.")
    except ExtractError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 1

    # context to stderr so stdout stays a clean, pipeable URL
    sys.stderr.write(
        f"# {episode['title']} [{episode['vod_media_type']}]\n"
        f"# {episode.get('sharing_url') or episode['canonical']}\n"
    )

    if args.json:
        json.dump(
            {
                "title": episode["title"],
                "canonical": episode["canonical"],
                "sharingUrl": episode["sharing_url"],
                "vodMediaType": episode["vod_media_type"],
                "ptmdTemplate": episode["ptmd_template"],
                "streams": streams,
            },
            sys.stdout,
            indent=2,
            ensure_ascii=False,
        )
        sys.stdout.write("\n")
        return 0

    if args.all:
        for s in streams:
            print(f"{s['mimeType']:24} {str(s['quality']):9} {s['uri']}")
        return 0

    if args.hls:
        best = pick_best_hls(streams)
        if not best:
            sys.stderr.write("error: no HLS stream available.\n")
            return 1
        print(best["uri"])
        return 0

    best = pick_best_progressive(streams)
    if not best:
        # no progressive file (rare) — fall back to HLS so we still emit something
        best = pick_best_hls(streams)
        if not best:
            sys.stderr.write("error: no usable stream found.\n")
            return 1
        sys.stderr.write("note: no progressive MP4; emitting HLS master.\n")
    print(best["uri"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
