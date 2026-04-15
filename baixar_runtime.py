#!/usr/bin/env python3
"""baixar_runtime.py - Baixa JDK 21 e Maven portateis para runtime/"""
import urllib.request, zipfile, shutil, sys, os
from pathlib import Path

RT  = Path(__file__).parent / "runtime"
JDK = RT / "jdk"
MVN = RT / "maven"
RT.mkdir(exist_ok=True)

JDK_URLS = [
    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3+9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip",
]
MVN_URLS = [
    "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip",
    "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip",
    "https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip",
    "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip",
]

def baixar(urls, dest, nome):
    for url in urls:
        try:
            print(f"  Tentando: {url.split('/')[2]}...", flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
                total = int(r.headers.get("Content-Length", 0))
                baixado = 0
                while True:
                    chunk = r.read(65536)
                    if not chunk: break
                    f.write(chunk)
                    baixado += len(chunk)
                    if total:
                        pct = int(baixado / total * 100)
                        mb  = baixado // 1024 // 1024
                        tmb = total   // 1024 // 1024
                        print(f"\r  {nome}: {pct}% ({mb}MB/{tmb}MB)   ", end="", flush=True)
            print(f"\n  OK: {nome} baixado!", flush=True)
            return True
        except Exception as e:
            print(f"\n  Falhou ({e}), tentando proximo...", flush=True)
            if Path(dest).exists(): Path(dest).unlink()
    return False

def extrair_flat(zip_path, dest_dir):
    """Extrai zip e move o conteudo interno diretamente para dest_dir."""
    tmp = zip_path.parent / "_tmp_extract"
    if tmp.exists(): shutil.rmtree(tmp)
    tmp.mkdir()
    with zipfile.ZipFile(zip_path, "r") as z:
        z.extractall(tmp)
    # Pega a unica subpasta gerada (ex: jdk-21.0.3+9  ou  apache-maven-3.9.6)
    subdirs = [d for d in tmp.iterdir() if d.is_dir()]
    if subdirs:
        if dest_dir.exists(): shutil.rmtree(dest_dir)
        shutil.move(str(subdirs[0]), str(dest_dir))
    shutil.rmtree(tmp, ignore_errors=True)
    zip_path.unlink(missing_ok=True)

# ── JDK ──────────────────────────────────────────────────────────────
def jdk_ok():
    if (JDK / "bin" / "java.exe").exists(): return True
    # Pode estar numa subpasta
    if JDK.exists():
        for sub in JDK.iterdir():
            if sub.is_dir() and (sub / "bin" / "java.exe").exists():
                return True
    return False

if not jdk_ok():
    print("[1/4] Baixando JDK 21 portatil... (~180 MB, apenas na 1a vez)", flush=True)
    ok = baixar(JDK_URLS, RT / "jdk.zip", "JDK")
    if not ok:
        print("ERRO: Nao foi possivel baixar o JDK. Verifique a internet.")
        sys.exit(1)
    print("[2/4] Extraindo JDK...", flush=True)
    extrair_flat(RT / "jdk.zip", JDK)
    if jdk_ok():
        print(f"  JDK instalado em: {JDK}", flush=True)
    else:
        print("ERRO: Extracao do JDK falhou.")
        sys.exit(1)
else:
    print("JDK 21 portatil: OK", flush=True)

# ── Maven ─────────────────────────────────────────────────────────────
def mvn_ok():
    if (MVN / "bin" / "mvn.cmd").exists(): return True
    if MVN.exists():
        for sub in MVN.iterdir():
            if sub.is_dir() and (sub / "bin" / "mvn.cmd").exists():
                return True
    return False

if not mvn_ok():
    print("[3/4] Baixando Maven... (~10 MB, apenas na 1a vez)", flush=True)
    ok = baixar(MVN_URLS, RT / "mvn.zip", "Maven")
    if not ok:
        print("AVISO: Nao foi possivel baixar o Maven. Usando Maven do sistema se disponivel.", flush=True)
    else:
        print("[4/4] Extraindo Maven...", flush=True)
        extrair_flat(RT / "mvn.zip", MVN)
        if mvn_ok():
            print(f"  Maven instalado em: {MVN}", flush=True)
        else:
            print("AVISO: Extracao do Maven falhou. Usando Maven do sistema se disponivel.", flush=True)
else:
    print("Maven portatil: OK", flush=True)


# ── ChromeDriver compatível ───────────────────────────────────────────
CHROMEDRIVER = RT / "chromedriver" / "chromedriver.exe"

def get_chrome_version():
    import subprocess, re
    try:
        import winreg
        for hive in [winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER]:
            for key in [r"SOFTWARE\Google\Chrome\BLBeacon",
                        r"SOFTWARE\WOW6432Node\Google\Chrome\BLBeacon"]:
                try:
                    with winreg.OpenKey(hive, key) as k:
                        ver, _ = winreg.QueryValueEx(k, "version")
                        return int(ver.split(".")[0])
                except: pass
    except: pass
    for path in [
        os.environ.get("LOCALAPPDATA","") + "\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    ]:
        if os.path.exists(path):
            try:
                r = subprocess.run([path, "--version"], capture_output=True, text=True, timeout=5)
                import re as re2
                m = re2.search(r"(\d+)\.\d+\.\d+\.\d+", r.stdout + r.stderr)
                if m: return int(m.group(1))
            except: pass
    return None

def chromedriver_ok():
    return CHROMEDRIVER.exists()

if not chromedriver_ok():
    print("[5/5] Detectando versão do Chrome para baixar ChromeDriver...", flush=True)
    major = get_chrome_version()
    if major is None:
        print("  AVISO: Chrome não detectado. Selenium Manager será usado.", flush=True)
    else:
        print(f"  Chrome versão: {major}", flush=True)
        try:
            import json
            req = urllib.request.Request(
                "https://googlechromelabs.github.io/chrome-for-testing/known-good-versions-with-downloads.json",
                headers={"User-Agent": "Mozilla/5.0"}
            )
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.loads(r.read())
            versoes = [v for v in data["versions"] if int(v["version"].split(".")[0]) == major]
            if versoes:
                ultima = versoes[-1]
                downloads = ultima.get("downloads", {}).get("chromedriver", [])
                url_win = next((d["url"] for d in downloads if d["platform"] == "win64"), None)
                if url_win:
                    print(f"  Baixando ChromeDriver {ultima['version']}...", flush=True)
                    cd_zip = RT / "chromedriver.zip"
                    cd_tmp = RT / "chromedriver_tmp"
                    # Limpa estado anterior
                    if cd_zip.exists(): cd_zip.unlink()
                    if cd_tmp.exists(): shutil.rmtree(cd_tmp, ignore_errors=True)

                    ok = baixar([url_win], cd_zip, "ChromeDriver")
                    if ok:
                        print("  Extraindo ChromeDriver...", flush=True)
                        cd_tmp.mkdir(exist_ok=True)
                        with zipfile.ZipFile(cd_zip, "r") as z:
                            z.extractall(cd_tmp)
                        # Acha o chromedriver.exe em qualquer subpasta
                        exe_src = None
                        for root, dirs, files in os.walk(cd_tmp):
                            for f in files:
                                if f == "chromedriver.exe":
                                    exe_src = Path(root) / f
                                    break
                            if exe_src: break
                        if exe_src:
                            CHROMEDRIVER.parent.mkdir(exist_ok=True)
                            shutil.copy2(exe_src, CHROMEDRIVER)
                            print(f"  ChromeDriver instalado: {CHROMEDRIVER}", flush=True)
                        # Limpa temporários
                        shutil.rmtree(cd_tmp, ignore_errors=True)
                        cd_zip.unlink(missing_ok=True)
                else:
                    print(f"  AVISO: URL win64 não encontrada para Chrome {major}.", flush=True)
            else:
                print(f"  AVISO: ChromeDriver para Chrome {major} não encontrado.", flush=True)
        except Exception as e:
            print(f"  AVISO: Falha ChromeDriver ({e}). Selenium Manager será usado.", flush=True)
else:
    print("ChromeDriver portatil: OK", flush=True)

print("", flush=True)
print("Runtime pronto!", flush=True)
