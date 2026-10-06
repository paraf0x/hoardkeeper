#!/usr/bin/env python3
"""Uploads one jar to Modrinth as a version of PROJECT, the way paraf0x's other mods publish: Fabric,
the game version from gradle.properties, Fabric API required, featured. A version number the project
already has is left alone (exit 0), so the release workflow can retry only what is missing.
Reads the token from MODRINTH_TOKEN. --dry-run prints the payload and touches nothing.
Usage: scripts/modrinth_publish.py --project P --version-number V --name N --channel release|beta
                                   --changelog FILE --jar FILE [--dry-run]"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "https://api.modrinth.com/v2"
USER_AGENT = "paraf0x/hoardkeeper (release workflow)"
FABRIC_API = "P7dR8mSH"


def gradle_property(name, path="gradle.properties"):
    with open(path, encoding="utf-8") as f:
        for line in f:
            key, sep, value = line.partition("=")
            if sep and key.strip() == name:
                return value.strip()
    sys.exit(f"{path} has no {name}")


def request(method, url, token, body=None, content_type=None):
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("User-Agent", USER_AGENT)
    req.add_header("Authorization", token)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as err:
        return err.code, err.read()


def existing_versions(project, token):
    status, body = request("GET", f"{API}/project/{urllib.parse.quote(project)}/version", token)
    if status != 200:
        sys.exit(f"Modrinth answered {status} for project {project}: {body.decode(errors='replace')}")
    return {v["version_number"] for v in json.loads(body)}


def multipart(payload, jar):
    boundary = uuid.uuid4().hex
    name = os.path.basename(jar)
    with open(jar, "rb") as f:
        data = f.read()
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"data\"\r\n"
        f"Content-Type: application/json\r\n\r\n".encode() + json.dumps(payload).encode() + b"\r\n",
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
        f"Content-Type: application/java-archive\r\n\r\n".encode() + data + b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--project", required=True)
    p.add_argument("--version-number", required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--channel", required=True, choices=["release", "beta", "alpha"])
    p.add_argument("--changelog", required=True)
    p.add_argument("--jar", required=True)
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    if not os.path.isfile(args.jar):
        sys.exit(f"no jar at {args.jar}")
    with open(args.changelog, encoding="utf-8") as f:
        changelog = f.read()

    payload = {
        "project_id": args.project,
        "version_number": args.version_number,
        "name": args.name,
        "changelog": changelog,
        "game_versions": [gradle_property("minecraft_version")],
        "loaders": ["fabric"],
        "dependencies": [{"project_id": FABRIC_API, "dependency_type": "required"}],
        "version_type": args.channel,
        "featured": args.channel == "release",
        "file_parts": ["file"],
        "primary_file": "file",
    }

    if args.dry_run:
        shown = dict(payload, changelog=f"<{len(changelog)} characters from {args.changelog}>")
        print(json.dumps(shown, indent=2))
        print(f"file: {args.jar} ({os.path.getsize(args.jar)} bytes)")
        return

    token = os.environ.get("MODRINTH_TOKEN", "").strip()
    if not token:
        sys.exit("MODRINTH_TOKEN is not set")

    # The project id in the payload has to be the id, not the slug.
    status, body = request("GET", f"{API}/project/{urllib.parse.quote(args.project)}", token)
    if status != 200:
        sys.exit(f"Modrinth answered {status} for project {args.project}: {body.decode(errors='replace')}")
    payload["project_id"] = json.loads(body)["id"]

    if args.version_number in existing_versions(args.project, token):
        print(f"{args.project} already has version {args.version_number} on Modrinth; nothing to do")
        return

    body, content_type = multipart(payload, args.jar)
    status, resp = request("POST", f"{API}/version", token, body, content_type)
    if not 200 <= status < 300:
        sys.exit(f"Modrinth answered {status}: {resp.decode(errors='replace')}")
    version = json.loads(resp)
    print(f"published {args.project} {version['version_number']} ({version['version_type']}), id {version['id']}")


if __name__ == "__main__":
    main()
