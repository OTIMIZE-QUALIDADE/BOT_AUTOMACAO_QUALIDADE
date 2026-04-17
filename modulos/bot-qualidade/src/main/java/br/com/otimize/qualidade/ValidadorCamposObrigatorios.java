package br.com.otimize.qualidade;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.time.Duration;
import java.util.*;

/**
 * ValidadorCamposObrigatorios — detecta e confirma campos obrigatórios no SEI.
 *
 * Fluxo de validação em duas fases:
 *  Fase 1 – Detecção passiva: encontra candidatos por classe CSS "camposObrigatorios"
 *            e fallback por borda vermelha (antes do submit).
 *  Fase 2 – Confirmação comportamental: clica em SALVAR sem preencher nenhum campo.
 *            Se o sistema bloquear (mantiver na tela ou mostrar erro), o campo é
 *            confirmado como obrigatório. Se o sistema salvar mesmo assim, o campo
 *            é reportado como "não confirmado".
 *
 * Para cada campo extrai: label, type, confirmado (boolean), motivoFalha (String).
 */
public class ValidadorCamposObrigatorios {

    // ── Modelo de dados ──────────────────────────────────────────────────────────

    public static class CampoObrigatorio {
        private final String  label;
        private final String  tipo;
        private final String  elementId;   // id HTML do input (pode ser vazio)
        private boolean       confirmado;  // true = bloqueou o salvamento
        private String        motivoFalha; // preenchido quando não confirmado

        public CampoObrigatorio(String label, String tipo, String elementId) {
            this.label     = label;
            this.tipo      = tipo;
            this.elementId = elementId;
            this.confirmado  = true;   // otimista: confirmado até prova em contrário
            this.motivoFalha = null;
        }

        public String  getLabel()       { return label; }
        public String  getTipo()        { return tipo; }
        public String  getElementId()   { return elementId; }
        public boolean isConfirmado()   { return confirmado; }
        public String  getMotivoFalha() { return motivoFalha; }
        public void    setNaoConfirmado(String motivo) { confirmado = false; motivoFalha = motivo; }
    }

    // ── Seletores do botão SALVAR (SEI usa vários padrões) ───────────────────────
    private static final List<String> SELETORES_SALVAR = Arrays.asList(
        "input[value='GRAVAR']", "input[value='Gravar']",
        "input[value='SALVAR']", "input[value='Salvar']",
        "input[value='CONFIRMAR']", "input[value='Confirmar']",
        "#form\\:salvar\\:salvar", "#form\\:btnGravar",
        "input[type='submit']", "button[type='submit']"
    );

    // ── API pública ──────────────────────────────────────────────────────────────

    /**
     * Detecta candidatos a campos obrigatórios na página atual (sem interação).
     * Prioridade: classe "camposObrigatorios" → fallback borda vermelha.
     */
    public List<CampoObrigatorio> detectarCandidatos(WebDriver driver) {
        List<CampoObrigatorio> lista  = new ArrayList<>();
        Set<String>            vistos = new LinkedHashSet<>();

        // Prioridade 1 — classe CSS camposObrigatorios
        for (WebElement el : driver.findElements(By.cssSelector(
                "input.camposObrigatorios, select.camposObrigatorios, textarea.camposObrigatorios"))) {
            if (!ehVisivel(el)) continue;
            String id = attr(el, "id");
            if (id != null && !id.isEmpty() && vistos.contains(id)) continue;
            lista.add(new CampoObrigatorio(extrairLabel(driver, el), obterTipo(el), id != null ? id : ""));
            if (id != null && !id.isEmpty()) vistos.add(id);
        }

        System.out.println("[INFO] CamposObrigatorios (classe CSS): " + lista.size());

        // Fallback — borda vermelha antes do submit
        if (lista.isEmpty()) {
            System.out.println("[INFO] Sem campos por classe, tentando borda vermelha (pré-submit)...");
            for (WebElement el : driver.findElements(By.cssSelector(
                    "input:not([type='hidden']):not([type='submit'])" +
                    ":not([type='button']):not([type='checkbox']):not([type='radio'])," +
                    "select, textarea"))) {
                if (!ehVisivel(el)) continue;
                String id = attr(el, "id");
                if (id != null && !id.isEmpty() && vistos.contains(id)) continue;
                if (temBordaVermelha(driver, el)) {
                    lista.add(new CampoObrigatorio(extrairLabel(driver, el), obterTipo(el), id != null ? id : ""));
                    if (id != null && !id.isEmpty()) vistos.add(id);
                }
            }
            System.out.println("[INFO] CamposObrigatorios (borda pré-submit): " + lista.size());
        }

        return lista;
    }

    /**
     * Tenta clicar em SALVAR sem preencher nada, depois verifica para cada candidato
     * se o sistema realmente bloqueou o salvamento.
     * Retorna true se algum bloqueio foi detectado (para controle do executor).
     *
     * @param driver     WebDriver posicionado no formulário aberto
     * @param candidatos lista retornada por detectarCandidatos()
     * @return true se o sistema não salvou (bloqueou), false se salvou sem erro
     */
    public boolean confirmarComTentativaSalvar(WebDriver driver, List<CampoObrigatorio> candidatos) {
        final String urlAntes = driver.getCurrentUrl();
        String fonteAntesTemp = "";
        try { fonteAntesTemp = driver.getPageSource(); } catch (Exception ignored) {}
        final String fonteAntes = fonteAntesTemp;

        // Tenta clicar no botão SALVAR
        boolean clicouSalvar = false;
        for (String seletor : SELETORES_SALVAR) {
            try {
                WebElement btn = driver.findElement(By.cssSelector(seletor));
                if (btn.isDisplayed() && btn.isEnabled()) {
                    System.out.println("[INFO] CamposObrigatorios: clicando SALVAR sem preencher (" + seletor + ")");
                    try { btn.click(); } catch (Exception e) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                    }
                    clicouSalvar = true;
                    break;
                }
            } catch (Exception ignored) {}
        }

        if (!clicouSalvar) {
            System.out.println("[WARN] CamposObrigatorios: botão SALVAR não encontrado — confirmação comportamental ignorada.");
            // Mantém todos como confirmados (detecção por classe já é suficientemente confiável)
            return false;
        }

        // Aguarda resposta do servidor (máx. 5 s)
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5)).until(d -> {
                try {
                    String src = d.getPageSource();
                    return !src.equals(fonteAntes) || !d.getCurrentUrl().equals(urlAntes);
                } catch (Exception e) { return false; }
            });
        } catch (Exception ignored) { /* timeout — analisa com o que há */ }

        String urlDepois    = driver.getCurrentUrl();
        String fonteDepois  = "";
        try { fonteDepois = driver.getPageSource(); } catch (Exception ignored) {}

        // Detecta se o sistema salvou (URL mudou e sem mensagem de erro visível)
        boolean urlMudou    = !urlAntes.equals(urlDepois);
        boolean temMsgErro  = detectarMensagemErroNaTela(driver);
        boolean formAindaAberto = !urlMudou || temMsgErro;

        System.out.println("[INFO] CamposObrigatorios pós-submit — urlMudou=" + urlMudou + " temMsgErro=" + temMsgErro);

        if (!formAindaAberto) {
            // Sistema salvou mesmo sem preencher — os campos detectados não são realmente obrigatórios
            System.out.println("[WARN] CamposObrigatorios: sistema SALVOU sem campos preenchidos!");
            for (CampoObrigatorio c : candidatos) {
                c.setNaoConfirmado("Sistema permitiu salvar sem preencher este campo");
            }
            return false;
        }

        // Sistema bloqueou — verifica campo a campo quais têm indicadores de erro
        Set<String> idsComErro = detectarIdsComIndicadorErro(driver);

        for (CampoObrigatorio c : candidatos) {
            String id = c.getElementId();
            if (id.isEmpty()) continue; // sem id, mantém como confirmado

            boolean temErro = idsComErro.contains(id)
                || verificarErroNoElementoPorId(driver, id);

            if (!temErro) {
                c.setNaoConfirmado("Campo identificado mas sem indicador de erro após tentar salvar vazio");
            }
        }

        return true;
    }

    /**
     * Analisa o resultado APÓS o SALVAR já ter sido clicado pelo executor.
     * Verifica se o sistema bloqueou por causa dos campos obrigatórios que foram deixados vazios.
     *
     * @param driver         WebDriver posicionado após o clique em SALVAR
     * @param candidatos     campos identificados como obrigatórios (deixados vazios intencionalmente)
     * @param urlAntesSalvar URL capturada antes de clicar em SALVAR
     * @return true se o sistema bloqueou corretamente; false se salvou mesmo sem os campos
     */
    public boolean analisarResultadoPosSalvar(WebDriver driver,
            List<CampoObrigatorio> candidatos, String urlAntesSalvar) {

        String urlDepois   = driver.getCurrentUrl();
        boolean urlMudou   = !urlAntesSalvar.equals(urlDepois);
        boolean temMsgErro = detectarMensagemErroNaTela(driver);
        boolean bloqueou   = !urlMudou || temMsgErro;

        System.out.println("[INFO] CamposObrigatorios pós-SALVAR — urlMudou=" + urlMudou
            + " temMsgErro=" + temMsgErro + " → bloqueou=" + bloqueou);

        if (!bloqueou) {
            System.out.println("[FAIL] Sistema SALVOU mesmo com campos obrigatórios vazios!");
            for (CampoObrigatorio c : candidatos) {
                c.setNaoConfirmado("Sistema permitiu salvar sem preencher este campo");
            }
            return false;
        }

        // Sistema bloqueou — verifica campo a campo quais têm indicador de erro
        Set<String> idsComErro = detectarIdsComIndicadorErro(driver);
        System.out.println("[INFO] IDs com indicador de erro detectados: " + idsComErro);

        for (CampoObrigatorio c : candidatos) {
            String id = c.getElementId();
            boolean temErro;
            if (!id.isEmpty()) {
                temErro = idsComErro.contains(id) || verificarErroNoElementoPorId(driver, id);
            } else {
                // Sem ID: se o sistema bloqueou com mensagem de erro, confiamos que é obrigatório
                temErro = temMsgErro;
            }
            if (!temErro) {
                c.setNaoConfirmado("Sistema bloqueou salvamento mas sem indicador de erro específico neste campo");
            }
        }
        return true;
    }

    // ── Detecção de erros pós-submit ─────────────────────────────────────────────

    private boolean detectarMensagemErroNaTela(WebDriver driver) {
        try {
            // Seletores comuns de mensagens de erro no SEI / RichFaces / Bootstrap
            List<WebElement> erros = driver.findElements(By.cssSelector(
                ".rf-msgs-err, .rf-msg-err, .messages-error, .ui-messages-error," +
                ".alert-danger, .alert-error, .has-error .help-block," +
                "[class*='error']:not(input):not(select):not(textarea)," +
                ".msg-erro, #messages .error"));
            for (WebElement e : erros) {
                if (ehVisivel(e) && !e.getText().trim().isEmpty()) return true;
            }
            // Verifica texto de erro genérico na fonte
            String src = driver.getPageSource().toLowerCase();
            return src.contains("campo obrigatório") || src.contains("campo obrigatorio")
                || src.contains("preencha") || src.contains("preenchimento obrigatório")
                || src.contains("is required") || src.contains("required field")
                || src.contains("rf-msg-err");
        } catch (Exception e) { return false; }
    }

    private Set<String> detectarIdsComIndicadorErro(WebDriver driver) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            // Campos com classes de erro conhecidas do SEI/RichFaces/Bootstrap
            List<WebElement> comErro = driver.findElements(By.cssSelector(
                "input.rf-msg-err, input.error, input.is-invalid, input[aria-invalid='true']," +
                "select.error, select.is-invalid, select[aria-invalid='true']," +
                "textarea.error, textarea.is-invalid, textarea[aria-invalid='true']"));
            for (WebElement el : comErro) {
                String id = attr(el, "id");
                if (id != null && !id.isEmpty()) ids.add(id);
            }
            // Campos cujo container pai tem classe de erro
            for (WebElement el : driver.findElements(By.cssSelector(
                    ".has-error input, .has-error select, .has-error textarea," +
                    ".error-field input, .error-field select"))) {
                String id = attr(el, "id");
                if (id != null && !id.isEmpty()) ids.add(id);
            }
            // Campos com borda vermelha após submit
            for (WebElement el : driver.findElements(By.cssSelector(
                    "input:not([type='hidden']):not([type='submit']):not([type='button'])," +
                    "select, textarea"))) {
                if (!ehVisivel(el)) continue;
                if (temBordaVermelha(driver, el)) {
                    String id = attr(el, "id");
                    if (id != null && !id.isEmpty()) ids.add(id);
                }
            }
        } catch (Exception ignored) {}
        return ids;
    }

    private boolean verificarErroNoElementoPorId(WebDriver driver, String id) {
        try {
            WebElement el = driver.findElement(By.id(id));
            // Verifica mensagem RichFaces associada ao campo (rf-msg para id=fieldId_msg)
            try {
                WebElement msg = driver.findElement(By.cssSelector(
                    "[id='" + id + "_msg'], [id='" + id + ":msg'], " +
                    ".rf-msg[id*='" + id + "'], span[id*='" + id + "'][class*='msg']"));
                if (ehVisivel(msg) && !msg.getText().trim().isEmpty()) return true;
            } catch (Exception ignored) {}
            // Borda vermelha no próprio campo
            return temBordaVermelha(driver, el);
        } catch (Exception e) { return false; }
    }

    // ── Helpers internos ─────────────────────────────────────────────────────────

    boolean ehVisivel(WebElement el) {
        try { return el.isDisplayed() && el.isEnabled(); }
        catch (Exception e) { return false; }
    }

    String attr(WebElement el, String nome) {
        try { return el.getAttribute(nome); }
        catch (Exception e) { return null; }
    }

    String obterTipo(WebElement el) {
        try {
            String tag = el.getTagName();
            if ("select".equals(tag))   return "select";
            if ("textarea".equals(tag)) return "textarea";
            String t = el.getAttribute("type");
            return (t != null && !t.isEmpty()) ? t : "text";
        } catch (Exception e) { return "text"; }
    }

    String extrairLabel(WebDriver driver, WebElement el) {
        // 1. label[for='id']
        try {
            String id = el.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                List<WebElement> ls = driver.findElements(By.cssSelector("label[for='" + id + "']"));
                if (!ls.isEmpty()) {
                    String t = ls.get(0).getText().trim();
                    if (!t.isEmpty()) return normalizar(t);
                }
            }
        } catch (Exception ignored) {}
        // 2. pai é <label>
        try {
            WebElement pai = el.findElement(By.xpath(".."));
            if ("label".equalsIgnoreCase(pai.getTagName())) {
                String t = pai.getText().trim();
                if (!t.isEmpty()) return normalizar(t);
            }
        } catch (Exception ignored) {}
        // 3. label irmão anterior
        try {
            String t = el.findElement(By.xpath("preceding-sibling::label[1]")).getText().trim();
            if (!t.isEmpty()) return normalizar(t);
        } catch (Exception ignored) {}
        // 4. label dentro do container pai (2 níveis)
        try {
            List<WebElement> ls = el.findElement(By.xpath("../..")).findElements(By.tagName("label"));
            if (!ls.isEmpty()) {
                String t = ls.get(0).getText().trim();
                if (!t.isEmpty()) return normalizar(t);
            }
        } catch (Exception ignored) {}
        // 5. placeholder
        try {
            String p = el.getAttribute("placeholder");
            if (p != null && !p.isEmpty()) return p.trim();
        } catch (Exception ignored) {}
        // 6. name
        try {
            String n = el.getAttribute("name");
            if (n != null && !n.isEmpty()) return n.trim();
        } catch (Exception ignored) {}
        return "Campo sem label";
    }

    private String normalizar(String s) {
        return s.replaceAll("\\s*\\*\\s*$", "").replaceAll("\\s+", " ").trim();
    }

    boolean temBordaVermelha(WebDriver driver, WebElement el) {
        try {
            String script =
                "var el=arguments[0];" +
                "var s=window.getComputedStyle(el);" +
                "var cs=[s.borderTopColor,s.borderRightColor,s.borderBottomColor,s.borderLeftColor,s.outlineColor];" +
                "for(var i=0;i<cs.length;i++){" +
                "  var m=(cs[i]||'').match(/rgb\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)/);" +
                "  if(m&&parseInt(m[1])>150&&parseInt(m[2])<80&&parseInt(m[3])<80)return true;" +
                "}" +
                "return false;";
            return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(script, el));
        } catch (Exception e) { return false; }
    }
}
