#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Otimize Qualidade - Servidor Principal
Porta: 9871
"""

import json
import os
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

PORT = 9871
BASE_DIR = Path(__file__).parent.resolve()
MODULOS_DIR = BASE_DIR / "modulos"
CENARIOS_DIR = BASE_DIR / "cenarios"
RELATORIOS_DIR = BASE_DIR / "relatorios"
RUNTIME = BASE_DIR / "runtime"
JDK_DIR = RUNTIME / "jdk"
MVN_DIR = RUNTIME / "maven"
UPLOADS_DIR = BASE_DIR / "uploads"
LOCATORS_FILE = BASE_DIR / "locators.json"

# Ensure directories exist
for d in [CENARIOS_DIR, RELATORIOS_DIR, UPLOADS_DIR]:
    d.mkdir(parents=True, exist_ok=True)

# Active executions
execucoes_ativas = {}
sse_queues = {}

def get_java_cmd():
    java = JDK_DIR / "bin" / "java.exe"
    return str(java) if java.exists() else "java"

def get_mvn_cmd():
    mvn = MVN_DIR / "bin" / "mvn.cmd"
    return str(mvn) if mvn.exists() else "mvn"

def compilar_bot(modulo_dir, exec_id):
    """Compile a bot module with Maven"""
    java_home = str(JDK_DIR) if JDK_DIR.exists() else None
    env = os.environ.copy()
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = str(JDK_DIR / "bin") + os.pathsep + env.get("PATH", "")

    mvn = get_mvn_cmd()
    cmd = [mvn, "clean", "package", "-q", "-DskipTests",
           "-Dmaven.repo.local=" + str(BASE_DIR / "runtime" / "m2")]

    send_sse(exec_id, f"Compilando módulo {modulo_dir.name}...", "info")
    # shell=True necessário no Windows para executar arquivos .cmd
    result = subprocess.run(cmd, cwd=str(modulo_dir), env=env,
                            capture_output=True, text=True, encoding="utf-8", errors="replace",
                            shell=sys.platform == "win32")
    if result.returncode != 0:
        erro = (result.stderr or result.stdout or "")[-1500:]
        send_sse(exec_id, f"Erro na compilação: {erro}", "error")
        return False
    send_sse(exec_id, "Compilação concluída.", "success")
    return True

def send_sse(exec_id, message, level="info"):
    if exec_id in sse_queues:
        sse_queues[exec_id].append({"level": level, "message": message, "time": time.strftime("%H:%M:%S")})

def escanear_tela(params, exec_id):
    """Scan a SEI screen and auto-generate a test scenario"""
    tela_url = params.get("telaUrl", "")
    sei_url = params.get("seiUrl", "")
    usuario = params.get("usuario", "")
    senha = params.get("senha", "")
    cenario_id = params.get("cenarioId", "").strip()
    descricao = params.get("descricao", "Cenário gerado automaticamente")
    modulo = params.get("modulo", "Geral")
    headless = params.get("headless", False)
    abas = params.get("abas", "").strip()

    if not tela_url or not sei_url or not usuario or not senha:
        send_sse(exec_id, "Parâmetros insuficientes para escanear.", "error")
        execucoes_ativas[exec_id] = "erro"
        return

    if not cenario_id:
        # Generate ID from URL
        cenario_id = tela_url.replace("/", "-").replace(".xhtml", "").strip("-")[:40]
        cenario_id = "scan-" + cenario_id

    modulo_dir = MODULOS_DIR / "bot-qualidade"
    jar_path = list((modulo_dir / "target").glob("*-with-dependencies.jar")) if (modulo_dir / "target").exists() else []

    if not jar_path:
        if not compilar_bot(modulo_dir, exec_id):
            execucoes_ativas[exec_id] = "erro"
            return
        jar_path = list((modulo_dir / "target").glob("*-with-dependencies.jar"))

    if not jar_path:
        send_sse(exec_id, "JAR não encontrado após compilação.", "error")
        execucoes_ativas[exec_id] = "erro"
        return

    saida_path = CENARIOS_DIR / f"{cenario_id}.json"

    java = get_java_cmd()
    cmd = [
        java, "-jar", str(jar_path[0]),
        "-m", "scan",
        "-u", sei_url,
        "-t", tela_url,
        "-c", cenario_id,
        "-d", descricao,
        "-mod", modulo,
        "-user", usuario,
        "-pass", senha,
        "-r", str(saida_path),
    ]
    if headless:
        cmd.append("-headless")
    if abas:
        cmd.extend(["-abas", abas])

    env = os.environ.copy()
    if JDK_DIR.exists():
        env["JAVA_HOME"] = str(JDK_DIR)
        env["PATH"] = str(JDK_DIR / "bin") + os.pathsep + env.get("PATH", "")

    send_sse(exec_id, f"Escaneando tela: {tela_url}", "info")
    send_sse(exec_id, "O Chrome abrirá automaticamente para analisar a tela...", "info")

    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace", env=env)
    execucoes_ativas[exec_id] = process

    for line in process.stdout:
        line = line.strip()
        if line:
            level = "error" if "[ERRO]" in line else "success" if "[OK]" in line or "[SCAN]" in line else "info"
            send_sse(exec_id, line, level)

    process.wait()

    if saida_path.exists():
        send_sse(exec_id, f"✅ Cenário '{cenario_id}' gerado com sucesso!", "success")
        send_sse(exec_id, f"Arquivo: {saida_path.name}", "success")
        send_sse(exec_id, "Revise os valores em dados_teste antes de executar.", "info")
        execucoes_ativas[exec_id] = "concluido"
    else:
        send_sse(exec_id, "Escaneamento falhou - cenário não foi gerado.", "error")
        execucoes_ativas[exec_id] = "erro"


def rodar_cenario(params, exec_id):
    """Run a test scenario"""
    cenario_id = params.get("cenarioId", "")
    url = params.get("seiUrl", "")
    usuario = params.get("usuario", "")
    senha = params.get("senha", "")
    operacoes = params.get("operacoes", ["inserir", "alterar", "excluir"])

    cenario_file = CENARIOS_DIR / f"{cenario_id}.json"
    if not cenario_file.exists():
        send_sse(exec_id, f"Cenário não encontrado: {cenario_id}", "error")
        execucoes_ativas[exec_id] = "erro"
        return

    modulo_dir = MODULOS_DIR / "bot-qualidade"
    jar_path = list((modulo_dir / "target").glob("*-with-dependencies.jar")) if (modulo_dir / "target").exists() else []

    if not jar_path:
        if not compilar_bot(modulo_dir, exec_id):
            execucoes_ativas[exec_id] = "erro"
            return
        jar_path = list((modulo_dir / "target").glob("*-with-dependencies.jar"))

    if not jar_path:
        send_sse(exec_id, "JAR não encontrado após compilação.", "error")
        execucoes_ativas[exec_id] = "erro"
        return

    relatorio_path = RELATORIOS_DIR / f"relatorio-{exec_id}.json"
    locators_path = LOCATORS_FILE if LOCATORS_FILE.exists() else BASE_DIR / "locators-default.json"

    java = get_java_cmd()
    cmd = [
        java, "-jar", str(jar_path[0]),
        "-c", cenario_id,
        "-f", str(cenario_file),
        "-u", url,
        "-user", usuario,
        "-pass", senha,
        "-o", ",".join(operacoes),
        "-r", str(relatorio_path),
        "-l", str(locators_path),
    ]

    env = os.environ.copy()
    if JDK_DIR.exists():
        env["JAVA_HOME"] = str(JDK_DIR)
        env["PATH"] = str(JDK_DIR / "bin") + os.pathsep + env.get("PATH", "")

    send_sse(exec_id, f"Iniciando cenário: {cenario_id}", "info")
    send_sse(exec_id, f"Operações: {', '.join(operacoes)}", "info")

    process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf-8", errors="replace", env=env)
    execucoes_ativas[exec_id] = process

    for line in process.stdout:
        line = line.strip()
        if line:
            level = "error" if "[ERRO]" in line else "success" if "[OK]" in line or "[PASS]" in line else "info"
            send_sse(exec_id, line, level)

    process.wait()

    if relatorio_path.exists():
        send_sse(exec_id, f"Relatório JSON: relatorio-{exec_id}.json", "success")
        html_path = RELATORIOS_DIR / f"relatorio-{exec_id}.html"
        if html_path.exists():
            send_sse(exec_id, f"📋 Ver relatório visual: http://localhost:{PORT}/relatorio/{exec_id}", "success")

    status = "concluido" if process.returncode == 0 else "erro"
    execucoes_ativas[exec_id] = status
    send_sse(exec_id, f"Execução {'concluída' if status == 'concluido' else 'com erros'}.", "success" if status == "concluido" else "error")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass  # Suppress default logging

    def serve_file(self, path, content_type="text/html; charset=utf-8"):
        try:
            with open(path, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", len(data))
            self.end_headers()
            self.wfile.write(data)
        except FileNotFoundError:
            self.send_error(404)

    def json_response(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", len(body))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        if path == "/" or path == "/index.html":
            self.serve_file(BASE_DIR / "index.html")
        elif path == "/cenarios.html":
            self.serve_file(BASE_DIR / "cenarios.html")
        elif path == "/api/cenarios":
            cenarios = []
            for f in sorted(CENARIOS_DIR.glob("*.json")):
                try:
                    data = json.loads(f.read_text(encoding="utf-8"))
                    cenarios.append({
                        "id": f.stem,
                        "descricao": data.get("descricao", f.stem),
                        "modulo": data.get("modulo", ""),
                        "operacoes": data.get("operacoes_disponiveis", ["inserir", "alterar", "excluir"]),
                    })
                except Exception:
                    pass
            self.json_response(cenarios)
        elif path.startswith("/cenarios/") and path.endswith(".json"):
            # Serve scenario JSON files statically
            cenario_id = path.split("/")[-1].replace(".json", "")
            cenario_file = CENARIOS_DIR / f"{cenario_id}.json"
            if cenario_file.exists():
                try:
                    data = json.loads(cenario_file.read_text(encoding="utf-8"))
                    self.json_response(data)
                except Exception as e:
                    self.json_response({"erro": str(e)}, 500)
            else:
                self.json_response({"erro": "Cenário não encontrado"}, 404)
        elif path == "/api/relatorios":
            relatorios = []
            for f in sorted(RELATORIOS_DIR.glob("relatorio-*.json"), reverse=True)[:20]:
                try:
                    data = json.loads(f.read_text(encoding="utf-8"))
                    relatorios.append({
                        "id": f.stem.replace("relatorio-", ""),
                        "cenario": data.get("cenario_id", ""),
                        "data": data.get("data_inicio", ""),
                        "status": data.get("status_geral", ""),
                        "total": data.get("total_steps", 0),
                        "passou": data.get("steps_passou", 0),
                        "falhou": data.get("steps_falhou", 0),
                    })
                except Exception:
                    pass
            self.json_response(relatorios)
        elif path.startswith("/api/relatorio/"):
            rel_id = path.split("/")[-1]
            rel_file = RELATORIOS_DIR / f"relatorio-{rel_id}.json"
            if rel_file.exists():
                self.json_response(json.loads(rel_file.read_text(encoding="utf-8")))
            else:
                self.json_response({"erro": "Relatório não encontrado"}, 404)
        elif path.startswith("/relatorio/"):
            # Serve HTML report directly in browser
            rel_id = path.split("/")[-1].replace(".html", "")
            html_file = RELATORIOS_DIR / f"relatorio-{rel_id}.html"
            if html_file.exists():
                self.serve_file(html_file)
            else:
                # Fallback: generate simple HTML from JSON
                json_file = RELATORIOS_DIR / f"relatorio-{rel_id}.json"
                if json_file.exists():
                    data = json.loads(json_file.read_text(encoding="utf-8"))
                    self._serve_inline_report(data)
                else:
                    self.send_error(404)
        elif path.startswith("/api/sse/"):
            exec_id = path.split("/")[-1]
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

            if exec_id not in sse_queues:
                sse_queues[exec_id] = []

            timeout = 300
            start = time.time()
            last_idx = 0

            while time.time() - start < timeout:
                queue = sse_queues.get(exec_id, [])
                if last_idx < len(queue):
                    for msg in queue[last_idx:]:
                        data = json.dumps(msg, ensure_ascii=False)
                        self.wfile.write(f"data: {data}\n\n".encode("utf-8"))
                        self.wfile.flush()
                    last_idx = len(queue)

                status = execucoes_ativas.get(exec_id)
                if status in ("concluido", "erro") and last_idx >= len(sse_queues.get(exec_id, [])):
                    self.wfile.write(b"data: {\"level\":\"done\",\"message\":\"fim\"}\n\n")
                    self.wfile.flush()
                    break

                time.sleep(0.3)
        elif path == "/api/status":
            java_ok = (JDK_DIR / "bin" / "java.exe").exists()
            bot_jar = list((MODULOS_DIR / "bot-qualidade" / "target").glob("*-with-dependencies.jar"))
            self.json_response({
                "java_ok": java_ok,
                "bot_compilado": len(bot_jar) > 0,
                "cenarios": len(list(CENARIOS_DIR.glob("*.json"))),
            })
        else:
            self.send_error(404)

    def _serve_inline_report(self, data):
        """Serve a minimal HTML report built from JSON data."""
        steps = data.get("steps", [])
        passou = data.get("steps_passou", 0)
        falhou = data.get("steps_falhou", 0)
        status_color = "#2ecc71" if data.get("status_geral") == "ok" else "#ff4757"
        rows = ""
        for i, s in enumerate(steps, 1):
            icon = "✅" if s.get("passou") else "❌"
            rows += f"<tr><td style='color:#8892b0'>{i}</td><td>{s.get('hora','')}</td><td>{icon}</td>"
            rows += f"<td><span style='background:rgba(108,99,255,.2);color:#6c63ff;padding:1px 6px;border-radius:3px;font-size:.7em'>{s.get('operacao','')}</span></td>"
            rows += f"<td>{s.get('descricao','')}<div style='color:{'#2ecc71' if s.get('passou') else '#ff4757'};font-size:.78em'>{s.get('mensagem','')}</div></td>"
            rows += f"<td style='color:#8892b0'>{s.get('duracao_ms',0)}ms</td></tr>"
        html = f"""<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8">
<title>Relatório {data.get('cenario_descricao','')}</title>
<style>*{{margin:0;padding:0;box-sizing:border-box}}body{{font-family:'Segoe UI',sans-serif;background:#0f1117;color:#e8eaf6;padding:24px}}
h1{{color:#6c63ff;font-size:1.3em;margin-bottom:4px}}.sub{{color:#8892b0;font-size:.82em;margin-bottom:20px}}
.sum{{display:flex;gap:12px;margin-bottom:20px}}.sc{{background:#1a1d2e;border:1px solid #2d3155;border-radius:8px;padding:14px;text-align:center;min-width:120px}}
.sv{{font-size:1.8em;font-weight:700}}.sl{{font-size:.72em;color:#8892b0;text-transform:uppercase}}
table{{width:100%;border-collapse:collapse;background:#1a1d2e;border-radius:8px;overflow:hidden}}
thead{{background:#252840}}th{{padding:10px 14px;text-align:left;font-size:.75em;text-transform:uppercase;color:#8892b0}}
td{{padding:8px 14px;font-size:.83em;border-bottom:1px solid #1a1d2e}}tr:hover td{{background:rgba(108,99,255,.08)}}</style></head>
<body><h1>⚡ {data.get('cenario_descricao','')}</h1>
<div class="sub">{data.get('data_inicio','')} | Status: <strong style="color:{status_color}">{data.get('status_geral','').upper()}</strong></div>
<div class="sum">
<div class="sc"><div class="sv" style="color:#6c63ff">{len(steps)}</div><div class="sl">Etapas</div></div>
<div class="sc"><div class="sv" style="color:#2ecc71">{passou}</div><div class="sl">Passaram</div></div>
<div class="sc"><div class="sv" style="color:#ff4757">{falhou}</div><div class="sl">Falharam</div></div>
</div>
<table><thead><tr><th>#</th><th>Hora</th><th>Status</th><th>Operação</th><th>Etapa / Mensagem</th><th>ms</th></tr></thead>
<tbody>{rows}</tbody></table></body></html>"""
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            params = json.loads(body)
        except Exception:
            params = {}

        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/rodar-cenario":
            exec_id = str(uuid.uuid4())[:8]
            sse_queues[exec_id] = []
            execucoes_ativas[exec_id] = "iniciando"
            thread = threading.Thread(target=rodar_cenario, args=(params, exec_id), daemon=True)
            thread.start()
            self.json_response({"exec_id": exec_id})

        elif path == "/api/recompilar":
            exec_id = str(uuid.uuid4())[:8]
            sse_queues[exec_id] = []
            modulo_dir = MODULOS_DIR / "bot-qualidade"
            # Delete target to force recompile
            import shutil
            target = modulo_dir / "target"
            if target.exists():
                shutil.rmtree(target)
            thread = threading.Thread(
                target=lambda: [compilar_bot(modulo_dir, exec_id),
                                setattr(execucoes_ativas, exec_id, "concluido")],
                daemon=True
            )
            thread.start()
            self.json_response({"exec_id": exec_id})

        elif path == "/api/escanear-tela":
            exec_id = str(uuid.uuid4())[:8]
            sse_queues[exec_id] = []
            execucoes_ativas[exec_id] = "iniciando"
            thread = threading.Thread(target=escanear_tela, args=(params, exec_id), daemon=True)
            thread.start()
            self.json_response({"exec_id": exec_id})

        elif path == "/api/salvar-cenario":
            cenario_id = params.get("id", "").strip()
            if not cenario_id:
                self.json_response({"erro": "ID obrigatório"}, 400)
                return
            cenario_file = CENARIOS_DIR / f"{cenario_id}.json"
            cenario_file.write_text(json.dumps(params, ensure_ascii=False, indent=2), encoding="utf-8")
            self.json_response({"ok": True, "id": cenario_id})

        elif path == "/api/atualizar-dados-teste":
            # Update test data values in scenario without exposing JSON to QA
            cenario_id = params.get("cenario_id", "").strip()
            campos = params.get("campos", [])  # [{campo, label, valor, valor_alterar}]
            campo_chave = params.get("campo_chave", "")

            if not cenario_id:
                self.json_response({"erro": "cenario_id obrigatório"}, 400)
                return

            cenario_file = CENARIOS_DIR / f"{cenario_id}.json"
            if not cenario_file.exists():
                self.json_response({"erro": "Cenário não encontrado"}, 404)
                return

            try:
                cenario = json.loads(cenario_file.read_text(encoding="utf-8"))

                # Build lookup by campo name
                valores = {c["campo"]: c for c in campos if c.get("campo")}

                # Update inserir acoes
                inserir = cenario.get("inserir", {})
                for acao in inserir.get("acoes", []):
                    nome = acao.get("campo", "")
                    if nome in valores and acao.get("tipo") in ("preencher", "selecionar"):
                        acao["valor"] = valores[nome].get("valor", acao.get("valor", ""))

                # Update alterar acoes
                alterar = cenario.get("alterar", {})
                for acao in alterar.get("acoes", []):
                    nome = acao.get("campo", "")
                    if nome in valores and acao.get("tipo") in ("preencher", "selecionar"):
                        v_alt = valores[nome].get("valor_alterar", "")
                        if v_alt:
                            acao["valor"] = v_alt
                            acao["valor_alterar"] = v_alt

                # Update campo_chave
                if campo_chave:
                    cenario["campo_chave"] = campo_chave

                cenario_file.write_text(json.dumps(cenario, ensure_ascii=False, indent=2), encoding="utf-8")
                self.json_response({"ok": True})
            except Exception as e:
                self.json_response({"erro": str(e)}, 500)

        elif path == "/api/salvar-locators":
            LOCATORS_FILE.write_text(json.dumps(params, ensure_ascii=False, indent=2), encoding="utf-8")
            self.json_response({"ok": True})

        else:
            self.send_error(404)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/api/cenarios/"):
            cenario_id = path.split("/")[-1]
            if not cenario_id or "/" in cenario_id or ".." in cenario_id:
                self.json_response({"erro": "ID inválido"}, 400)
                return
            cenario_file = CENARIOS_DIR / f"{cenario_id}.json"
            if not cenario_file.exists():
                self.json_response({"erro": "Cenário não encontrado"}, 404)
                return
            cenario_file.unlink()
            self.json_response({"ok": True, "id": cenario_id})
        else:
            self.send_error(404)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()


if __name__ == "__main__":
    print(f"[Otimize Qualidade] Servidor iniciando na porta {PORT}...")
    print(f"[Otimize Qualidade] Acesse: http://localhost:{PORT}")
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[Otimize Qualidade] Servidor encerrado.")
