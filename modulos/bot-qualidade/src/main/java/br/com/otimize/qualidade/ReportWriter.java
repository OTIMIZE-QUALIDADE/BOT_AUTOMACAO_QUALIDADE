package br.com.otimize.qualidade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ReportWriter {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public void salvar(String caminhoJson, TestScenario cenario, List<StepResult> resultados) {
        LocalDateTime agora = LocalDateTime.now();
        String dataHora = agora.format(FMT);

        long passou = resultados.stream().filter(StepResult::isPassou).count();
        long falhou = resultados.size() - passou;
        String statusGeral = falhou == 0 ? "ok" : "falha";

        // ── JSON Report ────────────────────────────────────────────────
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();

            root.put("cenario_id", cenario.getId());
            root.put("cenario_descricao", cenario.getDescricao());
            root.put("modulo", cenario.getModulo() != null ? cenario.getModulo() : "");
            root.put("data_inicio", dataHora);
            root.put("status_geral", statusGeral);
            root.put("total_steps", resultados.size());
            root.put("steps_passou", passou);
            root.put("steps_falhou", falhou);

            ArrayNode steps = root.putArray("steps");
            for (StepResult r : resultados) {
                ObjectNode step = steps.addObject();
                step.put("hora", r.getHora() != null ? r.getHora() : "");
                step.put("operacao", r.getOperacao());
                step.put("descricao", r.getDescricao());
                step.put("passou", r.isPassou());
                step.put("mensagem", r.getMensagem() != null ? r.getMensagem() : "");
                step.put("duracao_ms", r.getDuracaoMs());
                if (r.getScreenshotBase64() != null) {
                    step.put("screenshot_base64", r.getScreenshotBase64());
                }
            }

            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(caminhoJson), root);
            System.out.println("[INFO] Relatório JSON: " + caminhoJson);
        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao salvar JSON: " + e.getMessage());
        }

        // ── HTML Report ────────────────────────────────────────────────
        String htmlPath = caminhoJson.replace(".json", ".html");
        try {
            gerarHtml(htmlPath, cenario, resultados, dataHora, passou, falhou, statusGeral);
            System.out.println("[INFO] Relatório HTML: " + htmlPath);
            System.out.println("[REPORT_HTML] " + htmlPath);
        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao salvar HTML: " + e.getMessage());
        }

        // ── Console summary ────────────────────────────────────────────
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║               RELATÓRIO DE EXECUÇÃO                     ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf ("║  Cenário  : %-44s║%n", truncar(cenario.getDescricao(), 44));
        System.out.printf ("║  Módulo   : %-44s║%n", truncar(cenario.getModulo() != null ? cenario.getModulo() : "-", 44));
        System.out.printf ("║  Data/Hora: %-44s║%n", dataHora);
        System.out.printf ("║  Status   : %-44s║%n", falhou == 0 ? "✅ PASSOU" : "❌ FALHOU");
        System.out.printf ("║  Passou   : %-44s║%n", passou + " etapa(s)");
        System.out.printf ("║  Falhou   : %-44s║%n", falhou + " etapa(s)");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        for (StepResult r : resultados) {
            String icon = r.isPassou() ? "✅" : "❌";
            System.out.printf("║ %s [%s] %s (%s)%n",
                icon,
                padRight(r.getOperacao().toUpperCase(), 10),
                truncar(r.getDescricao(), 30),
                formatMs(r.getDuracaoMs()));
            if (!r.isPassou() && r.getMensagem() != null && !r.getMensagem().isEmpty()) {
                System.out.printf("║     → %s%n", truncar(r.getMensagem(), 50));
            }
        }
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    private void gerarHtml(String caminho, TestScenario cenario, List<StepResult> resultados,
                            String dataHora, long passou, long falhou, String status) throws Exception {
        long totalMs = resultados.stream().mapToLong(StepResult::getDuracaoMs).sum();

        try (PrintWriter pw = new PrintWriter(new FileWriter(caminho))) {
            pw.println("<!DOCTYPE html><html lang='pt-BR'><head><meta charset='UTF-8'>");
            pw.println("<title>Relatório - " + esc(cenario.getDescricao()) + "</title>");
            pw.println("<style>");
            pw.println("*{margin:0;padding:0;box-sizing:border-box}");
            pw.println("body{font-family:'Segoe UI',sans-serif;background:#0f1117;color:#e8eaf6;padding:24px}");
            pw.println(".container{max-width:960px;margin:0 auto}");
            pw.println("h1{color:#6c63ff;font-size:1.35em;margin-bottom:3px}");
            pw.println(".subtitle{color:#8892b0;font-size:0.82em;margin-bottom:20px}");
            pw.println(".summary{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:22px}");
            pw.println(".sum-card{background:#1a1d2e;border:1px solid #2d3155;border-radius:8px;padding:14px;text-align:center}");
            pw.println(".sum-val{font-size:1.9em;font-weight:700;margin-bottom:3px}");
            pw.println(".sum-label{font-size:0.72em;color:#8892b0;text-transform:uppercase;letter-spacing:0.5px}");
            pw.println(".c-ok{color:#2ecc71}.c-err{color:#ff4757}.c-warn{color:#ffa502}.c-info{color:#6c63ff}");
            pw.println("table{width:100%;border-collapse:collapse;background:#1a1d2e;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.4)}");
            pw.println("thead{background:#252840}");
            pw.println("th{padding:10px 14px;text-align:left;font-size:0.75em;text-transform:uppercase;letter-spacing:0.5px;color:#8892b0}");
            pw.println("td{padding:8px 14px;font-size:0.83em;border-bottom:1px solid #1a1d2e}");
            pw.println("tr:nth-child(even) td{background:rgba(37,40,64,.4)}");
            pw.println("tr:hover td{background:rgba(108,99,255,.08)}");
            pw.println(".op-badge{display:inline-block;padding:1px 7px;border-radius:3px;font-size:0.68em;font-weight:700;text-transform:uppercase}");
            pw.println(".op-inserir{background:rgba(0,212,170,.2);color:#00d4aa}");
            pw.println(".op-alterar{background:rgba(255,165,2,.2);color:#ffa502}");
            pw.println(".op-excluir{background:rgba(255,71,87,.2);color:#ff4757}");
            pw.println(".op-default{background:rgba(108,99,255,.2);color:#6c63ff}");
            pw.println(".op-validacao{background:rgba(52,152,219,.2);color:#3498db}");
            pw.println(".pill{padding:5px 16px;border-radius:20px;font-weight:700;font-size:0.88em;display:inline-block}");
            pw.println(".pill-ok{background:rgba(46,204,113,.2);color:#2ecc71;border:1px solid #2ecc71}");
            pw.println(".pill-fail{background:rgba(255,71,87,.2);color:#ff4757;border:1px solid #ff4757}");
            pw.println(".header-row{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:6px;gap:12px}");
            pw.println("details summary{cursor:pointer;color:#8892b0;font-size:0.75em;margin-top:4px}");
            pw.println("details img{max-width:100%;border-radius:4px;border:1px solid #2d3155;margin-top:6px}");
            pw.println(".footer{margin-top:22px;text-align:center;color:#8892b0;font-size:0.72em;padding-top:12px;border-top:1px solid #2d3155}");
            pw.println(".hora-cell{color:#8892b0;font-family:monospace;font-size:0.8em;white-space:nowrap}");
            pw.println("</style></head><body><div class='container'>");

            // Header
            pw.println("<div class='header-row'>");
            pw.println("<div><h1>⚡ " + esc(cenario.getDescricao()) + "</h1>");
            pw.println("<div class='subtitle'>Módulo: <strong>" + esc(cenario.getModulo() != null ? cenario.getModulo() : "Geral")
                + "</strong> &nbsp;|&nbsp; " + dataHora + " &nbsp;|&nbsp; Duração total: " + formatMs(totalMs) + "</div></div>");
            pw.println("<span class='pill " + ("ok".equals(status) ? "pill-ok" : "pill-fail") + "'>"
                + ("ok".equals(status) ? "✅ PASSOU" : "❌ FALHOU") + "</span>");
            pw.println("</div>");

            // Summary
            pw.println("<div class='summary'>");
            pw.println("<div class='sum-card'><div class='sum-val c-info'>" + resultados.size() + "</div><div class='sum-label'>Total de Etapas</div></div>");
            pw.println("<div class='sum-card'><div class='sum-val c-ok'>" + passou + "</div><div class='sum-label'>Passaram</div></div>");
            pw.println("<div class='sum-card'><div class='sum-val " + (falhou > 0 ? "c-err" : "c-ok") + "'>" + falhou + "</div><div class='sum-label'>Falharam</div></div>");
            pw.println("<div class='sum-card'><div class='sum-val c-warn'>" + formatMs(totalMs) + "</div><div class='sum-label'>Tempo Total</div></div>");
            pw.println("</div>");

            // Steps table
            pw.println("<table><thead><tr><th>#</th><th>Hora</th><th>Status</th><th>Operação</th><th>Etapa / Mensagem</th><th>Duração</th></tr></thead><tbody>");

            int idx = 1;
            for (StepResult r : resultados) {
                String opKey = r.getOperacao().toLowerCase();
                String opClass = opKey.contains("inserir") ? "op-inserir"
                    : opKey.contains("alterar") ? "op-alterar"
                    : opKey.contains("excluir") ? "op-excluir"
                    : opKey.contains("validacao") ? "op-validacao"
                    : "op-default";

                pw.println("<tr>");
                pw.println("<td style='color:#8892b0;text-align:center'>" + idx++ + "</td>");
                pw.println("<td class='hora-cell'>" + (r.getHora() != null ? r.getHora() : "") + "</td>");
                pw.println("<td style='text-align:center;font-size:1.1em'>" + (r.isPassou() ? "✅" : "❌") + "</td>");
                pw.println("<td><span class='op-badge " + opClass + "'>" + esc(r.getOperacao()) + "</span></td>");

                pw.print("<td><div>" + esc(r.getDescricao()) + "</div>");
                if (r.getMensagem() != null && !r.getMensagem().isEmpty()) {
                    String msgClass = r.isPassou() ? "c-ok" : "c-err";
                    pw.print("<div class='" + msgClass + "' style='font-size:0.78em;margin-top:2px'>→ " + esc(r.getMensagem()) + "</div>");
                }
                if (r.getScreenshotBase64() != null) {
                    pw.print("<details><summary>Ver screenshot</summary><img src='data:image/png;base64,"
                        + r.getScreenshotBase64() + "' /></details>");
                }
                pw.println("</td>");
                pw.println("<td style='color:#8892b0;white-space:nowrap'>" + formatMs(r.getDuracaoMs()) + "</td>");
                pw.println("</tr>");
            }

            pw.println("</tbody></table>");
            pw.println("<div class='footer'>Gerado por <strong style='color:#6c63ff'>Otimize Qualidade</strong> &nbsp;|&nbsp; " + dataHora + "</div>");
            pw.println("</div></body></html>");
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String truncar(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    private String formatMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }
}
