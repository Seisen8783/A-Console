"""Publish one desktop installer to GitHub and Gitee."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory


GITHUB_REPO = "Seisen8783/A-Console"
GITEE_REPO = "songxiuyu2077/A-Console"
GITEE_REMOTE = f"git@gitee.com:{GITEE_REPO}.git"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def github_release(tag, title, installer):
    command = ["gh", "release", "view", tag, "--repo", GITHUB_REPO,
               "--json", "assets,isDraft,url"]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if not installer or result.stderr.strip() != "release not found":
        raise SystemExit(result.stderr.strip() or "Could not read GitHub Release")
    subprocess.run(
        ["gh", "release", "create", tag, "--repo", GITHUB_REPO, "--verify-tag",
         "--title", title, "--generate-notes", "--prerelease", "--draft"],
        check=True,
    )
    return json.loads(subprocess.check_output(command, text=True))


def publish_to_github(tag, release, paths):
    assets = {asset["name"]: asset for asset in release["assets"]}
    missing = []
    for path in paths:
        asset = assets.get(path.name)
        if asset is None:
            missing.append(path)
        elif asset.get("digest") != f"sha256:{sha256(path)}":
            raise SystemExit(f"GitHub already has different contents for {path.name}")
    if missing:
        subprocess.run(
            ["gh", "release", "upload", tag, "--repo", GITHUB_REPO, *missing],
            check=True,
        )


def publish_to_gitee(directory, paths):
    def git(*args, **kwargs):
        return subprocess.run(["git", "-C", directory, *args], check=True, **kwargs)

    git("init", "--quiet")
    git("config", "core.autocrlf", "false")
    git("fetch", "--depth=1", "--no-tags", GITEE_REMOTE, "refs/heads/main")
    git("checkout", "--quiet", "--detach", "FETCH_HEAD")
    installers = directory / "installers"
    installers.mkdir(exist_ok=True)
    to_add = []
    for path in paths:
        target = installers / path.name
        if target.exists():
            if sha256(target) != sha256(path):
                raise SystemExit(f"Gitee already has different contents for {path.name}")
        else:
            shutil.copyfile(path, target)
            to_add.append(target)
    if to_add:
        for setting in ("user.name", "user.email"):
            value = subprocess.check_output(["git", "config", setting], text=True).strip()
            git("config", setting, value)
        git("add", "--", *to_add)
        git("commit", "-m", f"Publish {paths[0].name} on Gitee")
        git("push", GITEE_REMOTE, "HEAD:refs/heads/main")
    print(f"Gitee installer (sign-in required): https://gitee.com/{GITEE_REPO}/raw/main/installers/{paths[0].name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", help="windows-vX.Y.Z or macos-vX.Y.Z")
    parser.add_argument("installer", nargs="?", type=Path, help="built installer")
    args = parser.parse_args()
    match = re.fullmatch(r"(windows|macos)-v(\d+\.\d+\.\d+)", args.tag)
    if not match:
        parser.error("the tag must be windows-vX.Y.Z or macos-vX.Y.Z")
    platform, version = match.groups()
    label, suffix = {
        "windows": ("Windows", "Windows-x64.exe"),
        "macos": ("macOS", "macOS.dmg"),
    }[platform]
    filename = f"A-Console-{version}-{suffix}"
    if args.installer and (args.installer.name != filename or not args.installer.is_file()):
        parser.error(f"expected the built installer named {filename}")

    release = github_release(args.tag, f"A Console {label} {version}", args.installer)
    with TemporaryDirectory(prefix="a-console-release-") as temporary:
        directory = Path(temporary)
        installer = args.installer.resolve() if args.installer else None
        if installer is None:
            subprocess.run(
                ["gh", "release", "download", args.tag, "--repo", GITHUB_REPO,
                 "--pattern", filename, "--dir", directory],
                check=True,
            )
            installer = directory / filename
        checksum = directory / f"{filename}.sha256"
        line_ending = "\r\n" if platform == "windows" else "\n"
        checksum.write_bytes(f"{sha256(installer)}  {filename}{line_ending}".encode("ascii"))
        paths = [installer, checksum]
        publish_to_github(args.tag, release, paths)
        gitee_checkout = directory / "gitee"
        gitee_checkout.mkdir()
        publish_to_gitee(gitee_checkout, paths)
        if release["isDraft"]:
            subprocess.run(
                ["gh", "release", "edit", args.tag, "--repo", GITHUB_REPO, "--draft=false"],
                check=True,
            )
    print(f"GitHub installer: https://github.com/{GITHUB_REPO}/releases/download/{args.tag}/{filename}")


if __name__ == "__main__":
    main()
