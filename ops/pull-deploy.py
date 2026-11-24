#!/usr/bin/env python3
"""Deploy the artifact of the successful GitHub CI run for the tip of main."""

import argparse
import hashlib
import json
import os
import pwd
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zipfile import ZipFile


REPO = "lawground-team/LawGround"
API = f"https://api.github.com/repos/{REPO}"
BASE = Path("/srv/dis-docker-data/lawground-deploy")
RELEASES = BASE / "releases"
CURRENT = BASE / "current"
STATE = BASE / "state.json"
SHA = re.compile(r"[a-f0-9]{40}")
MAX_BUNDLE = 200 * 1024 * 1024
WEB_HEALTH = "http://127.0.0.1:18081/actuator/health/readiness"
AI_HEALTH = "http://127.0.0.1:18001/internal/v1/health/ready"
REQUIREMENTS = ("requirements.txt", "requirements-models.txt")


def request_json(url, headers=None, timeout=20):
    req = Request(url, headers={"User-Agent": "LawGround-pull-deploy", **(headers or {})})
    with urlopen(req, timeout=timeout) as response:
        return json.load(response)


def candidate(current_sha):
    ref = request_json(f"{API}/git/ref/heads/main")
    sha = ref["object"]["sha"]
    if ref["ref"] != "refs/heads/main" or not SHA.fullmatch(sha):
        raise ValueError("GitHub returned an invalid main ref")
    if current_sha == sha:
        print(f"main {sha} is already deployed")
        return None

    params = urlencode({"branch": "main", "event": "push", "head_sha": sha, "per_page": 1})
    runs = request_json(f"{API}/actions/workflows/ci.yml/runs?{params}")["workflow_runs"]
    if not runs:
        print(f"No main push CI run for {sha} yet")
        return None
    run = runs[0]
    if (
        run["head_sha"] != sha
        or run["head_branch"] != "main"
        or run["event"] != "push"
        or run["head_repository"]["full_name"].lower() != REPO.lower()
    ):
        raise ValueError("CI run does not belong to this main push")
    if run["status"] != "completed" or run["conclusion"] != "success":
        print(f"CI for {sha} is {run['conclusion'] or run['status']}; keeping current release")
        return None
    tag = f"deploy-{sha}-{int(run['id'])}-{int(run['run_attempt'])}"
    try:
        release = request_json(f"{API}/releases/tags/{tag}")
    except HTTPError as error:
        if error.code != 404:
            raise
        print(f"CI passed for {sha}, but no deployment release exists for this run")
        return None
    if release["tag_name"] != tag or release["target_commitish"] != sha or release["draft"]:
        raise ValueError("GitHub release does not match the successful run")
    name = f"lawground-{sha}.tar.gz"
    assets = [asset for asset in release["assets"] if asset["name"] == name]
    if len(assets) != 1:
        raise ValueError("The deployment release must contain exactly one matching bundle")
    asset = assets[0]
    url = f"https://github.com/{REPO}/releases/download/{tag}/{name}"
    if (
        asset["browser_download_url"].lower() != url.lower()
        or asset["state"] != "uploaded"
        or not 0 < asset["size"] <= MAX_BUNDLE
        or not re.fullmatch(r"sha256:[a-f0-9]{64}", asset.get("digest") or "")
    ):
        raise ValueError("GitHub deployment asset metadata is incomplete or invalid")
    return sha, tag, url, asset["size"], asset["digest"]


def fetch_bundle(destination, url, size, digest):
    request = Request(url, headers={"User-Agent": "LawGround-pull-deploy"})
    hasher = hashlib.sha256()
    length = 0
    with urlopen(request, timeout=45) as response, destination.open("wb") as output:
        while block := response.read(1024 * 1024):
            length += len(block)
            if length > size:
                raise ValueError("Deployment bundle exceeds its published size")
            hasher.update(block)
            output.write(block)
    if length != size or f"sha256:{hasher.hexdigest()}" != digest:
        raise ValueError("Deployment bundle does not match the GitHub asset digest")


def run(*command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def prepare_release(sha, url, size, digest, current):
    release = RELEASES / sha
    if (release / ".prepared").is_file():
        if (release / ".prepared").read_text().strip() == digest:
            return release
        if release == current or release.is_symlink():
            raise ValueError(f"Cannot replace the current release {sha}")
        shutil.rmtree(release)
    if shutil.disk_usage(BASE).free < 5 * 1024**3:
        raise OSError("Less than 5 GiB free on the deployment disk")
    with tempfile.TemporaryDirectory(dir=BASE) as directory:
        scratch = Path(directory)
        bundle = scratch / "bundle.tgz"
        fetch_bundle(bundle, url, size, digest)
        unpack = scratch / "unpacked"
        unpack.mkdir()
        with tarfile.open(bundle, "r:gz") as archive:
            members = archive.getmembers()
            if sum(member.size for member in members) > 2 * MAX_BUNDLE:
                raise ValueError("Unpacked deployment bundle is too large")
            for member in members:
                if not (member.isfile() or member.isdir()):
                    raise ValueError("Deployment bundle contains a link or special file")
            archive.extractall(unpack, members=members, filter="data")
        if (
            {child.name for child in unpack.iterdir()} != {"REVISION", "app.jar", "ai-service"}
            or (unpack / "REVISION").read_text().strip() != sha
            or not (unpack / "ai-service/app/main.py").is_file()
        ):
            raise ValueError("Bundle contents do not match the CI commit")
        migration_fingerprint(unpack / "app.jar")
        # Requirements are the only reason to build a new environment. Reuse the
        # actual environment path so pruning a release cannot break another one.
        if all(
            (unpack / "ai-service" / name).read_bytes() == (current / "ai-service" / name).read_bytes()
            for name in REQUIREMENTS
        ):
            environment = (current / "venv").resolve(strict=True)
        else:
            environment = create_environment(unpack / "ai-service")
        os.symlink(environment, unpack / "venv")
        (unpack / ".prepared").write_text(digest + "\n")
        (unpack / "ai-service").chmod(0o755)
        (unpack / "app.jar").chmod(0o644)
        unpack.rename(release)
    return release


def create_environment(source):
    digest = hashlib.sha256()
    for filename in REQUIREMENTS:
        digest.update(hashlib.sha256((source / filename).read_bytes()).digest())
    environment = BASE / "envs" / digest.hexdigest()
    marker = environment.with_suffix(".ready")
    if marker.is_file() and (environment / "bin/python").is_file():
        return environment
    environment.mkdir(mode=0o755, exist_ok=True)
    user = pwd.getpwnam("lawground")
    os.chown(environment, user.pw_uid, user.pw_gid)
    # The app user must be able to read the requirements, but root must
    # never write files below the app-owned virtual environment.
    with tempfile.TemporaryDirectory(dir=BASE) as directory:
        specs = Path(directory)
        specs.chmod(0o755)
        for name in REQUIREMENTS:
            shutil.copyfile(source / name, specs / name)
            (specs / name).chmod(0o644)
        as_app = ("runuser", "-u", "lawground", "--")
        run(*as_app, "/usr/bin/python3.12", "-m", "venv", str(environment))
        pip = (*as_app, str(environment / "bin/python"), "-m", "pip")
        run(*pip, "install", "--no-cache-dir", "--index-url", "https://download.pytorch.org/whl/cpu", "torch")
        run(*pip, "install", "--no-cache-dir", "-r", str(specs / REQUIREMENTS[0]), "-r", str(specs / REQUIREMENTS[1]))
        run(*pip, "check")
    marker.touch()
    return environment


def migration_fingerprint(jar):
    with ZipFile(jar) as archive:
        if "META-INF/MANIFEST.MF" not in archive.namelist():
            raise ValueError("Invalid Spring jar")
        return {
            name: hashlib.sha256(archive.read(name)).hexdigest()
            for name in archive.namelist()
            if name.startswith("BOOT-INF/classes/db/migration/") and not name.endswith("/")
        }


def backup_database(previous, sha):
    fd, name = tempfile.mkstemp(prefix=".pg-", dir=BASE / "backups")
    path = Path(name)
    try:
        with os.fdopen(fd, "wb") as output:
            run("docker", "exec", "lawground-postgres-1", "pg_dump", "-U", "lawground", "-d", "lawground", "-Fc", stdout=output)
        path.replace(BASE / "backups" / f"before-{sha}.dump")
        print(f"Saved PostgreSQL backup before changing migrations from {previous.name} to {sha}")
    finally:
        path.unlink(missing_ok=True)


def switch(release):
    temporary = BASE / ".current-next"
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(release)
    temporary.replace(CURRENT)


def health(url, expected, headers=None):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if request_json(url, headers, timeout=4)["status"] == expected:
                return
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(2)
    raise RuntimeError(f"{url} did not become {expected}")


def restart_and_check():
    token = Path("/etc/lawground/internal-token").read_text().strip()
    run("systemctl", "restart", "lawground-ai.service")
    health(AI_HEALTH, "READY", {"X-Internal-Token": token})
    run("systemctl", "restart", "lawground-web.service")
    health(WEB_HEALTH, "UP")


def save_state(state):
    fd, name = tempfile.mkstemp(prefix=".state-", dir=BASE)
    path = Path(name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as file:
            json.dump(state, file)
            file.flush()
            os.fsync(file.fileno())
        path.replace(STATE)
    finally:
        path.unlink(missing_ok=True)


def rollback(state):
    pending = state["pending"]
    previous = RELEASES / pending["previous"]
    switch(previous)
    restart_and_check()
    state["last_failed"] = pending["tag"]
    del state["pending"]
    save_state(state)
    print(f"Restored {previous.name}; rejected {pending['tag']}")


def prune(state, deployed):
    keep = {deployed.name, state["baseline"], state["previous"]}
    for release in RELEASES.iterdir():
        if SHA.fullmatch(release.name) and release.name not in keep and release.is_dir() and not release.is_symlink():
            shutil.rmtree(release)
    environments = {(RELEASES / name / "venv").resolve() for name in keep}
    for env in (BASE / "envs").iterdir():
        if re.fullmatch(r"[a-f0-9]{64}", env.name) and env not in environments and env.is_dir() and not env.is_symlink():
            shutil.rmtree(env)
            env.with_suffix(".ready").unlink(missing_ok=True)
    backups = sorted((BASE / "backups").glob("before-*.dump"), key=lambda path: path.stat().st_mtime, reverse=True)
    for old in backups[2:]:
        old.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Report the candidate without deploying")
    args = parser.parse_args()
    state = json.loads(STATE.read_text())
    if "pending" in state:
        if args.check:
            print(f"Interrupted deployment will restore {state['pending']['previous']}")
            return
        rollback(state)
    current = CURRENT.resolve(strict=True)
    if current.parent != RELEASES or not SHA.fullmatch(current.name):
        raise ValueError("Invalid active release path")
    selected = candidate(current.name)
    if selected is None:
        return
    sha, tag, url, size, digest = selected
    if state.get("last_failed") == tag:
        print(f"{tag} failed its last health check; waiting for another successful CI attempt")
        return
    print(f"Eligible: {sha} from {tag}", flush=True)
    if args.check:
        return
    release = prepare_release(sha, url, size, digest, current)
    if request_json(f"{API}/git/ref/heads/main")["object"]["sha"] != sha:
        print(f"main advanced while downloading {sha}; keeping current release")
        return
    if migration_fingerprint(release / "app.jar") != migration_fingerprint(current / "app.jar"):
        backup_database(current, sha)
    state["pending"] = {"previous": current.name, "tag": tag}
    save_state(state)
    try:
        switch(release)
        restart_and_check()
    except (OSError, RuntimeError, subprocess.SubprocessError):
        rollback(state)
        raise
    del state["pending"]
    state["previous"] = current.name
    save_state(state)
    print(f"Deployed {sha}; both services are healthy", flush=True)
    prune(state, release)


if __name__ == "__main__":
    main()
