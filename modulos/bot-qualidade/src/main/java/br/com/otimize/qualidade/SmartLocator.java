package br.com.otimize.qualidade;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

/**
 * SmartLocator — encontra elementos usando múltiplas estratégias.
 *
 * CORREÇÕES v2:
 * - Tempo de espera reduzido: 800ms por tentativa CSS (não mais 3s)
 * - Scroll automático: rola a página até o elemento antes de retornar
 * - Busca em toda a página (inclusive footer fixo): botões no final da tela
 * - Case-insensitive: "Novo", "NOVO", "novo" são equivalentes
 * - SCREEN-CHANGE suprimido: só imprime uma vez, não em cada campo
 */
public class SmartLocator {

    private static final int CSS_WAIT_MS = 60;    // Tempo máximo por tentativa CSS
    private static final int QUICK_WAIT_MS = 40;  // Tempo para estratégias semânticas

    private final WebDriver driver;
    private final int waitMs;

    public SmartLocator(WebDriver driver, int waitMs) {
        this.driver = driver;
        this.waitMs = waitMs;
    }

    /**
     * Encontra elemento usando seletores fornecidos + estratégias semânticas de fallback.
     * silencioso=true suprime diagnosticarTela() para campos opcionais/condicionais.
     */
    public WebElement encontrar(List<String> localizadores, String labelText, String elementType) {
        return encontrar(localizadores, labelText, elementType, false);
    }

    public WebElement encontrar(List<String> localizadores, String labelText, String elementType, boolean silencioso) {

        // ── Estratégia 1: CSS/XPath seletores ────────────────────────────────────
        if (localizadores != null) {
            for (String sel : localizadores) {
                WebElement el = sel.startsWith("//") || sel.startsWith("(//")
                    ? tentarXPath(sel)
                    : tentarCSS(sel);
                if (el != null) return scrollTo(el);
            }
        }

        // ── Estratégia 2: Texto do label (mais robusto a mudanças de ID) ─────────
        if (labelText != null && !labelText.isEmpty()) {
            WebElement el = encontrarPorLabel(labelText);
            if (el != null) {
                System.out.println("[SMART] Elemento encontrado por label '" + labelText + "'");
                return scrollTo(el);
            }
        }

        // ── Estratégia 3: Placeholder ──────────────────────────────────────────────
        if (labelText != null && !labelText.isEmpty()) {
            WebElement el = encontrarPorPlaceholder(labelText);
            if (el != null) {
                System.out.println("[SMART] Elemento encontrado por placeholder '" + labelText + "'");
                return scrollTo(el);
            }
        }

        // ── Estratégia 4: Atributo name (match parcial) ───────────────────────────
        if (labelText != null && !labelText.isEmpty()) {
            WebElement el = encontrarPorNome(labelText);
            if (el != null) {
                System.out.println("[SMART] Elemento encontrado por name/id parcial '" + labelText + "'");
                return scrollTo(el);
            }
        }

        // ── Estratégia 5: aria-label ──────────────────────────────────────────────
        if (labelText != null && !labelText.isEmpty()) {
            WebElement el = encontrarPorAriaLabel(labelText);
            if (el != null) {
                System.out.println("[SMART] Elemento encontrado por aria-label '" + labelText + "'");
                return scrollTo(el);
            }
        }

        // ── Estratégia 6: Texto do botão (case-insensitive + scroll page) ─────────
        if ("button".equals(elementType) && labelText != null) {
            // Aliases: "Salvar" pode ser "GRAVAR", "Salvar" pode ser "Salvar e Fechar", etc.
            java.util.List<String> tentativas = new java.util.ArrayList<>();
            tentativas.add(labelText);
            String lowerLabel = labelText.toLowerCase();
            if (lowerLabel.contains("salvar") || lowerLabel.contains("gravar") || lowerLabel.contains("save")) {
                tentativas.add("GRAVAR"); tentativas.add("Gravar"); tentativas.add("salvar"); tentativas.add("Salvar e Fechar");
            }
            if (lowerLabel.contains("excluir") || lowerLabel.contains("delete") || lowerLabel.contains("remover")) {
                tentativas.add("EXCLUIR"); tentativas.add("Excluir"); tentativas.add("Remover");
            }
            if (lowerLabel.contains("confirmar") || lowerLabel.contains("confirm")) {
                tentativas.add("SIM"); tentativas.add("OK"); tentativas.add("Confirmar");
            }
            for (String txt : tentativas) {
                WebElement el = encontrarBotaoPorTexto(txt);
                if (el != null) {
                    System.out.println("[SMART] Botão encontrado por texto '" + txt + "' (buscado: '" + labelText + "')");
                    return scrollTo(el);
                }
            }
            // Se não achou visível, rola para o final da página e tenta de novo
            scrollFimPagina();
            for (String txt : tentativas) {
                WebElement el = encontrarBotaoPorTexto(txt);
                if (el != null) {
                    System.out.println("[SMART] Botão encontrado no final da página '" + txt + "'");
                    return scrollTo(el);
                }
            }

            // ── Estratégia 7: Classe CSS do botão (padrão otm:commandButton do SEI) ──
            // otm:commandButton renderiza como <a class="btn btn-dark btn-gravar">
            java.util.List<String[]> classesCandidatas = new java.util.ArrayList<>();
            if (lowerLabel.contains("salvar") || lowerLabel.contains("gravar") || lowerLabel.contains("save")) {
                classesCandidatas.add(new String[]{
                    "a.btn-gravar, button.btn-gravar",
                    "[id$=':salvar'], [id$=':gravar']",
                    "a.btn-dark:not(.btn-novo):not(.btn-excluir):not(.btn-consultar)"
                });
            }
            if (lowerLabel.contains("excluir") || lowerLabel.contains("delete")) {
                classesCandidatas.add(new String[]{"a.btn-excluir, button.btn-excluir", "[id$=':excluir']"});
            }
            if (lowerLabel.contains("novo") || lowerLabel.contains("new")) {
                classesCandidatas.add(new String[]{"a.btn-novo, button.btn-novo", "[id$=':novo']"});
            }
            for (String[] sels : classesCandidatas) {
                for (String sel : sels) {
                    WebElement el = tentarCSS(sel);
                    if (el != null) {
                        System.out.println("[SMART] Botão encontrado por classe CSS '" + sel + "'");
                        return scrollTo(el);
                    }
                }
            }
        }

        // ── Tudo falhou: diagnóstico resumido ──────────────────────────────────────
        String msg = "Elemento não encontrado. Label='" + labelText + "', Seletores tentados: "
            + String.join(", ", localizadores != null ? localizadores : List.of());
        if (!silencioso) diagnosticarTela(labelText);
        throw new NoSuchElementException(msg);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Métodos privados
    // ─────────────────────────────────────────────────────────────────────────────

    private WebElement tentarCSS(String sel) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(CSS_WAIT_MS));
            WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(sel)));
            if (el != null && el.isDisplayed()) return el;
        } catch (Exception ignored) {}
        return null;
    }

    private WebElement tentarXPath(String xpath) {
        try {
            List<WebElement> els = driver.findElements(By.xpath(xpath));
            for (WebElement el : els) {
                try { if (el.isDisplayed()) return el; } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Rola a página até o elemento para garantir que está no viewport */
    private WebElement scrollTo(WebElement el) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({behavior:'instant', block:'center'});", el);
            Thread.sleep(80);
        } catch (Exception ignored) {}
        return el;
    }

    /** Rola até o final da página (para botões no footer) */
    private void scrollFimPagina() {
        try {
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");
            Thread.sleep(200);
        } catch (Exception ignored) {}
    }

    private WebElement encontrarPorLabel(String labelText) {
        // Usa translate() para busca case-insensitive
        String lowerLabel = labelText.toLowerCase();
        String[] xpaths = {
            // Label exato (case-insensitive)
            "//label[translate(normalize-space(text()),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='"
                + lowerLabel + "']/following-sibling::*[1][self::input or self::select or self::textarea]",
            "//label[translate(normalize-space(text()),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='"
                + lowerLabel + "']/..//input[not(@type='hidden')]",
            "//label[translate(normalize-space(text()),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='"
                + lowerLabel + "']/..//select",
            // Contém (parcial)
            "//label[contains(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'"
                + lowerLabel + "')]/..//input[not(@type='hidden')]",
            // for=id
            "//*[@id=//label[contains(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'"
                + lowerLabel + "')]/@for]",
        };
        for (String xpath : xpaths) {
            WebElement el = tentarXPath(xpath);
            if (el != null) return el;
        }
        return null;
    }

    private WebElement encontrarPorPlaceholder(String text) {
        String lower = text.toLowerCase();
        try {
            for (WebElement el : driver.findElements(By.tagName("input"))) {
                try {
                    String ph = el.getAttribute("placeholder");
                    if (ph != null && ph.toLowerCase().contains(lower) && el.isDisplayed()) return el;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private WebElement encontrarPorNome(String text) {
        String lower = text.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (lower.isEmpty()) return null;
        String[] sels = {
            // Prioriza seletores JSF que terminam com o nome do campo
            "input[id$=':" + lower + "'], select[id$=':" + lower + "'], textarea[id$=':" + lower + "']",
            "button[id$=':" + lower + "'], a[id$=':" + lower + "']",
            // Fallback para contém parcial
            "input[id*='" + lower + "'], select[id*='" + lower + "'], textarea[id*='" + lower + "']",
            "input[name*='" + lower + "'], select[name*='" + lower + "']",
            "button[id*='" + lower + "'], a[id*='" + lower + "']",
        };
        for (String sel : sels) {
            try {
                for (WebElement el : driver.findElements(By.cssSelector(sel))) {
                    if (el.isDisplayed()) return el;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private WebElement encontrarPorAriaLabel(String text) {
        String lower = text.toLowerCase();
        try {
            for (WebElement el : driver.findElements(By.cssSelector("[aria-label]"))) {
                try {
                    String al = el.getAttribute("aria-label");
                    if (al != null && al.toLowerCase().contains(lower) && el.isDisplayed()) return el;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Busca botão por texto — case-insensitive, procura em button/input/a/span.
     * Inclui busca por elementos mesmo fora do viewport (via JS scrollIntoView posterior).
     */
    private WebElement encontrarBotaoPorTexto(String texto) {
        String lower = texto.toLowerCase().trim();
        // XPath com translate para case-insensitive
        String ci = "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
        String[] xpaths = {
            "//button[" + ci + "='" + lower + "']",
            "//input[@type='submit' and translate(normalize-space(@value),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='" + lower + "']",
            "//a[" + ci + "='" + lower + "']",
            "//button[contains(" + ci + ",'" + lower + "')]",
            "//a[contains(" + ci + ",'" + lower + "')]",
            "//*[@value and translate(normalize-space(@value),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='" + lower + "']",
            // Busca por span/i dentro de botão (ícone + texto)
            "//button[.//*[" + ci + "='" + lower + "']]",
            "//a[.//*[" + ci + "='" + lower + "']]",
        };
        for (String xpath : xpaths) {
            try {
                List<WebElement> els = driver.findElements(By.xpath(xpath));
                for (WebElement el : els) {
                    try {
                        // Aceita elementos fora do viewport também — o scroll cuida depois
                        if (el.isEnabled()) return el;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Diagnóstico resumido — imprime labels e botões visíveis na página atual.
     * Chamado apenas quando TODAS as estratégias falham.
     */
    private void diagnosticarTela(String elementoProcurado) {
        try {
            System.out.println("[DIAGNÓSTICO] Elemento '" + elementoProcurado + "' não encontrado na página.");
            System.out.println("[DIAGNÓSTICO] URL atual: " + driver.getCurrentUrl());

            // Labels
            List<WebElement> labels = driver.findElements(By.tagName("label"));
            if (!labels.isEmpty()) {
                System.out.println("[DIAGNÓSTICO] Labels visíveis:");
                for (WebElement lbl : labels) {
                    try {
                        String txt = lbl.getText().trim();
                        String forAttr = lbl.getAttribute("for");
                        if (!txt.isEmpty() && txt.length() < 80)
                            System.out.println("[DIAGNÓSTICO]   label='" + txt + "'" + (forAttr != null && !forAttr.isEmpty() ? " for='" + forAttr + "'" : ""));
                    } catch (Exception ignored) {}
                }
            }

            // Botões (incluindo os do final da página)
            scrollFimPagina();
            List<WebElement> botoes = driver.findElements(
                By.cssSelector("button, input[type=submit], input[type=button], a[class*='btn'], a[id*='btn']"));
            if (!botoes.isEmpty()) {
                System.out.println("[DIAGNÓSTICO] Botões na página:");
                for (WebElement btn : botoes) {
                    try {
                        String txt = btn.getText().trim();
                        String val = btn.getAttribute("value");
                        String id = btn.getAttribute("id");
                        String texto = txt.isEmpty() ? val : txt;
                        if (texto != null && !texto.isEmpty())
                            System.out.println("[DIAGNÓSTICO]   botão='" + texto + "' id='" + id + "'");
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            System.out.println("[DIAGNÓSTICO] Erro ao analisar tela: " + e.getMessage());
        }
    }

    /** Captura fingerprint da tela atual para detecção de mudanças */
    public ScreenFingerprint capturarFingerprint(String telaId) {
        ScreenFingerprint fp = new ScreenFingerprint(telaId);
        try {
            for (WebElement lbl : driver.findElements(By.tagName("label"))) {
                String txt = lbl.getText().trim();
                if (!txt.isEmpty()) fp.addLabel(txt);
            }
            for (WebElement inp : driver.findElements(By.cssSelector("input:not([type=hidden]), select, textarea"))) {
                try {
                    if (inp.isDisplayed()) fp.addField(inp.getAttribute("id") + "|" + inp.getAttribute("name"));
                } catch (Exception ignored) {}
            }
            for (WebElement btn : driver.findElements(By.cssSelector("button, input[type=submit], a[id*='btn']"))) {
                try {
                    String txt = btn.getText().trim();
                    String val = btn.getAttribute("value");
                    String texto = txt.isEmpty() ? val : txt;
                    if (texto != null && !texto.isEmpty()) fp.addButton(texto);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.out.println("[WARN] Erro ao capturar fingerprint: " + e.getMessage());
        }
        return fp;
    }
}
