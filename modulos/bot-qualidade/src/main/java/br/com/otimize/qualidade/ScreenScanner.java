package br.com.otimize.qualidade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * ScreenScanner - Escaneia qualquer tela do SEI e gera um cenário com dados fictícios prontos.
 *
 * Correções v3:
 * - Gera dados fictícios inteligentes automaticamente (nome, CPF, data, etc.)
 * - "valor" agora é preenchido diretamente nas acoes (não apenas em dados_teste)
 * - Placeholder de máscara de data (__/__/____) não é mais usado como label
 * - campo_chave detectado automaticamente
 * - Login usa WebDriverWait ao invés de Thread.sleep fixo
 */
public class ScreenScanner {

    private final WebDriver driver;
    private final Config config;
    private final SmartLocator locator;

    // Padrões de placeholder que são máscaras de input, não labels reais
    private static final Set<String> PLACEHOLDERS_MASCARAS = new HashSet<>(Arrays.asList(
        "__/__/____", "__/__/__ __:__", "__:__", "dd/mm/aaaa", "dd/mm/yyyy",
        "mm/aaaa", "___.___.___-__", "__.___.___/____-__", "(__) _____-____",
        "____-____", "_____-___"
    ));

    public ScreenScanner(WebDriver driver, Config config) {
        this.driver = driver;
        this.config = config;
        this.locator = new SmartLocator(driver, config.getWaitMs());
    }

    public void escanear(String telaUrl, String cenarioId, String descricao, String modulo, String saida, String abasManual) {
        System.out.println("[SCAN] Iniciando escaneamento da tela: " + telaUrl);

        try {
            loginSei();
        } catch (Exception e) {
            System.out.println("[ERRO] Login falhou: " + e.getMessage());
            return;
        }

        String urlCons = config.getSeiUrl() + telaUrl;
        try {
            System.out.println("[SCAN] Navegando para tela de listagem (Cons): " + urlCons);
            driver.get(urlCons);
            esperarPaginaCarregar(3);
        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao navegar: " + e.getMessage());
        }

        String pageTitle = driver.getTitle();
        String pageSource = driver.getPageSource();
        if ((pageTitle != null && (pageTitle.contains("500") || pageTitle.contains("404") || pageTitle.contains("Error")))
                || pageSource.contains("HTTP Status 500") || pageSource.contains("HTTP Status 404")
                || pageSource.contains("Internal Server Error")) {
            System.out.println("[ERRO] A tela retornou erro HTTP. A URL '" + telaUrl + "' possivelmente não existe neste SEI.");
            System.out.println("[SCAN] Dica: navegue manualmente no SEI, abra a tela desejada e copie a URL da barra de endereço.");
            return;
        }

        ScreenFingerprint fpLista = locator.capturarFingerprint(cenarioId + "-cons");
        System.out.println("[SCAN] Tela Cons: " + fpLista.toSummary());

        String urlAtualAntesDoBotao = driver.getCurrentUrl();

        boolean abrindoForm = false;
        String[] botoesTentativa = {"Novo", "Adicionar", "Incluir", "New", "Cadastrar", "Inserir"};
        for (String botao : botoesTentativa) {
            try {
                WebElement btn = locator.encontrar(
                    List.of("#form\\:btnNovo", "a[id*='btnNovo']", "button[id*='novo']",
                            "input[value='" + botao + "']", "a[id*='Novo']", "a[id*='novo']"),
                    botao, "button");
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                esperarPaginaCarregar(2);
                abrindoForm = true;
                System.out.println("[SCAN] Clicou no botão '" + botao + "'.");
                break;
            } catch (Exception ignored) {}
        }

        if (!abrindoForm) {
            System.out.println("[SCAN] Botão 'Novo' não encontrado. Escaneando campos da página atual.");
        }

        String urlDepoisDoBotao = driver.getCurrentUrl();
        String telaForm;
        String baseUrl = config.getSeiUrl();
        boolean urlMudouParaHttp = !urlDepoisDoBotao.equals(urlAtualAntesDoBotao)
                && !urlDepoisDoBotao.startsWith("data:")
                && (urlDepoisDoBotao.startsWith("http://") || urlDepoisDoBotao.startsWith("https://"));
        if (urlMudouParaHttp) {
            telaForm = urlDepoisDoBotao.replace(baseUrl, "").replaceAll("\\?.*", "");
            System.out.println("[SCAN] 'Novo' redirecionou para tela Form: " + telaForm);
        } else {
            if (urlDepoisDoBotao.startsWith("data:")) {
                System.out.println("[SCAN] URL 'data:' detectada após clique — formulário tratado como inline.");
            } else {
                System.out.println("[SCAN] Formulário abre na mesma página (inline).");
            }
            telaForm = telaUrl;
        }

        String formSource = driver.getPageSource();
        if (formSource.contains("HTTP Status 500") || formSource.contains("Internal Server Error")) {
            System.out.println("[SCAN] Aviso: Tela do formulário retornou HTTP 500. Pode precisar de dados pré-cadastrados.");
        }

        ScreenFingerprint fpForm = locator.capturarFingerprint(cenarioId + "-form");
        System.out.println("[SCAN] Tela Form: " + fpForm.toSummary());

        // Fecha modal de verificação (panelCpf) se aberto — para não contaminar o scan
        fecharModalVerificacao();

        // Pequena espera extra: AJAX do formulário pode precisar de tempo adicional após modal
        esperarAjax(800);

        // Se campos estão dentro de um iframe (padrão em alguns módulos do SEI),
        // muda o contexto do WebDriver para esse frame antes de escanear
        switchToFormIframe();

        String urlFormulario = driver.getCurrentUrl();

        // ── Detecta nomes das abas (strings, não WebElements — evita StaleElement) ─────
        List<String> nomesAbas;
        if (abasManual != null && !abasManual.trim().isEmpty()) {
            nomesAbas = new ArrayList<>();
            for (String n : abasManual.split(",")) {
                String t = n.trim();
                if (!t.isEmpty()) nomesAbas.add(t);
            }
            System.out.println("[SCAN] Abas manuais: " + nomesAbas);
        } else {
            nomesAbas = detectarNomesAbas();
            if (!nomesAbas.isEmpty())
                System.out.println("[SCAN] " + nomesAbas.size() + " aba(s) detectada(s): " + nomesAbas);
        }

        List<CampoDetectado> campos = new ArrayList<>();

        if (nomesAbas.isEmpty()) {
            // ── Sem abas: fluxo original com campos condicionais ──────────────────────
            System.out.println("[SCAN] Sem abas — scan direto em 3 passos.");

            List<CampoDetectado> camposIniciais = escanearCampos();
            System.out.println("[SCAN] Passo 1 – inicial: " + camposIniciais.size() + " campo(s).");

            Set<String> idsCondicionais = new HashSet<>();
            escanearCamposCondicionais(camposIniciais, idsCondicionais);

            campos = escanearCampos();
            for (CampoDetectado c : campos) {
                if (idsCondicionais.contains(c.id + "|" + c.name)) {
                    for (CampoDetectado orig : camposIniciais) {
                        if (orig.condicionalSelectLabel != null
                                && (orig.id + "|" + orig.name).equals(c.id + "|" + c.name)) {
                            c.condicionalSelectLabel = orig.condicionalSelectLabel;
                            c.condicionalSelectValor = orig.condicionalSelectValor;
                            break;
                        }
                    }
                }
            }
            System.out.println("[SCAN] Passo 3 – re-scan: " + campos.size() + " campo(s).");

        } else {
            // ── Com abas: clica cada aba, escaneia e detecta condicionais por aba ─────
            Set<String> vistos = new HashSet<>();

            for (String nomeAba : nomesAbas) {
                System.out.println("[SCAN] ── Escaneando aba: " + nomeAba);

                boolean clicou = clicarAbaPorTexto(nomeAba);
                if (!clicou) {
                    System.out.println("[SCAN] Aba não encontrada: " + nomeAba);
                    continue;
                }
                esperarAjax(1200);

                // Volta para o formulário se a URL mudou ao clicar na aba
                if (!driver.getCurrentUrl().equals(urlFormulario)) {
                    System.out.println("[SCAN] URL mudou ao clicar aba '" + nomeAba + "', voltando...");
                    driver.get(urlFormulario);
                    esperarAjax(2000);
                    clicarAbaPorTexto(nomeAba);
                    esperarAjax(1000);
                }

                List<CampoDetectado> camposIniciais = escanearCampos();
                System.out.println("[SCAN]   Passo 1: " + camposIniciais.size() + " campo(s) na aba.");

                Set<String> idsCondicionais = new HashSet<>();
                escanearCamposCondicionais(camposIniciais, idsCondicionais);

                List<CampoDetectado> camposAba = escanearCampos();
                System.out.println("[SCAN]   Passo 3: " + camposAba.size() + " campo(s) após condicionais.");

                int novosNaAba = 0;
                for (CampoDetectado c : camposAba) {
                    c.aba = nomeAba;
                    if (idsCondicionais.contains(c.id + "|" + c.name)) {
                        for (CampoDetectado orig : camposIniciais) {
                            if (orig.condicionalSelectLabel != null
                                    && (orig.id + "|" + orig.name).equals(c.id + "|" + c.name)) {
                                c.condicionalSelectLabel = orig.condicionalSelectLabel;
                                c.condicionalSelectValor = orig.condicionalSelectValor;
                                break;
                            }
                        }
                    }
                    String key = (c.id != null ? c.id : "") + "|" + (c.name != null ? c.name : "");
                    if (!vistos.contains(key)) {
                        vistos.add(key);
                        campos.add(c);
                        novosNaAba++;
                    }
                }
                System.out.println("[SCAN]   " + novosNaAba + " campo(s) novo(s) na aba '" + nomeAba + "'.");
            }
        }

        System.out.println("[SCAN] Total de campos: " + campos.size());
        for (CampoDetectado c : campos) {
            System.out.println("[SCAN]   " + c);
        }

        if (campos.isEmpty()) {
            System.out.println("[SCAN] Nenhum campo detectado. Possíveis causas:");
            System.out.println("[SCAN]   1. A tela usa iframe - tente a URL Form direta");
            System.out.println("[SCAN]   2. Os campos carregam dinamicamente");
            System.out.println("[SCAN]   3. URL incorreta");
        }

        List<BotaoDetectado> botoes = escanearBotoes();
        System.out.println("[SCAN] " + botoes.size() + " botão(ões) detectado(s):");
        for (BotaoDetectado b : botoes) {
            System.out.println("[SCAN]   " + b);
        }

        gerarCenario(cenarioId, descricao, modulo, telaUrl, telaForm, campos, botoes, saida);
        System.out.println("[SCAN] Cenário gerado: " + saida);
    }

    private void loginSei() throws Exception {
        System.out.println("[SCAN] Fazendo login...");
        driver.get(config.getSeiUrl());
        // Aguarda campo de usuário ao invés de sleep fixo
        new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("input[type='text'], input[id*='usuario'], #form\\:usuario")));

        WebElement userField = locator.encontrar(
            List.of("input[id*='usuario']", "#form\\:usuario", "input[name='usuario']", "input[type='text']"),
            "Usuário", "input");
        userField.clear();
        userField.sendKeys(config.getUsuario());

        WebElement passField = locator.encontrar(
            List.of("input[type='password']", "#form\\:senha", "input[name='senha']"),
            "Senha", "input");
        passField.clear();
        passField.sendKeys(config.getSenha());

        WebElement submit = locator.encontrar(
            List.of("input[type='submit']", "#form\\:btnEntrar", "button[type='submit']"),
            "Entrar", "button");
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", submit);

        // Aguarda URL mudar (sai da página de login) — mais rápido que sleep fixo
        String urlLogin = driver.getCurrentUrl();
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15))
                .until(d -> !d.getCurrentUrl().equals(urlLogin));
        } catch (Exception e) {
            // Verifica manualmente se login falhou
        }
        System.out.println("[SCAN] Login realizado. URL atual: " + driver.getCurrentUrl());
    }

    private void esperarPaginaCarregar(int segundos) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(segundos))
                .until(d -> ((JavascriptExecutor) d)
                    .executeScript("return document.readyState").equals("complete"));
        } catch (Exception ignored) {}
    }

    private void esperarAjax(long ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5))
                .until(d -> "complete".equals(
                    ((JavascriptExecutor) d).executeScript("return document.readyState")));
        } catch (Exception ignored) {}
    }

    /**
     * Detecta nomes das abas como Strings — NÃO retorna WebElements para evitar StaleElement.
     * Usa múltiplos seletores CSS, XPath e JavaScript em cascata.
     */
    @SuppressWarnings("unchecked")
    private List<String> detectarNomesAbas() {
        String[] seletoresCss = {
            "a.rf-tbpnl-lnk", "span.rf-tbpnl-lbl", "div.rf-tbpnl-tab", "li.rf-tbpnl-tab",
            "a.rf-tab-lnk",   "span.rf-tab-lbl",   "div.rf-tab-hdr",   "div.rf-tab",
            "[id*='headerLink']", "[id*=':header']",
            "[class*='tab-hdr']", "[class*='tabHdr']",
            "li.rf-tab-hdr", "li.rf-tab-hdr-act",
            ".ui-tabs-nav a", ".ui-tabs-tab a", "ul.nav-tabs a", "ul.nav-tabs li",
            "[role='tab']",
            "li[class*='tab']:not([class*='tab-cnt']):not([class*='tab-con'])"
        };
        for (String sel : seletoresCss) {
            try {
                List<WebElement> found = driver.findElements(By.cssSelector(sel));
                List<String> nomes = new ArrayList<>();
                for (WebElement el : found) {
                    try {
                        if (!el.isDisplayed()) continue;
                        String txt = el.getText().trim();
                        if (txt.isEmpty()) {
                            Object tc = ((JavascriptExecutor) driver)
                                .executeScript("return arguments[0].textContent;", el);
                            if (tc != null) txt = tc.toString().trim();
                        }
                        if (!txt.isEmpty() && !nomes.contains(txt)) nomes.add(txt);
                    } catch (Exception ignored) {}
                }
                if (nomes.size() >= 2) {
                    System.out.println("[SCAN] Abas via CSS '" + sel + "': " + nomes);
                    return nomes;
                }
            } catch (Exception ignored) {}
        }
        // XPath fallback
        String[] xpaths = {
            "//*[contains(@class,'tab') and @role='tab']",
            "//a[contains(@id,'header') and @href]",
            "//*[contains(@class,'tab') and count(../*[contains(@class,'tab')])>1]"
        };
        for (String xp : xpaths) {
            try {
                List<WebElement> found = driver.findElements(By.xpath(xp));
                List<String> nomes = new ArrayList<>();
                for (WebElement el : found) {
                    try {
                        if (!el.isDisplayed()) continue;
                        String txt = el.getText().trim();
                        if (txt.isEmpty()) {
                            Object tc = ((JavascriptExecutor) driver)
                                .executeScript("return arguments[0].textContent;", el);
                            if (tc != null) txt = tc.toString().trim();
                        }
                        if (!txt.isEmpty() && !nomes.contains(txt)) nomes.add(txt);
                    } catch (Exception ignored) {}
                }
                if (nomes.size() >= 2) {
                    System.out.println("[SCAN] Abas via XPath: " + nomes);
                    return nomes;
                }
            } catch (Exception ignored) {}
        }
        // JavaScript fallback
        try {
            List<String> jsResult = (List<String>) ((JavascriptExecutor) driver).executeScript(
                "var todos = Array.from(document.querySelectorAll('a,div,li,span'));" +
                "var grupos = {};" +
                "todos.forEach(function(el) {" +
                "  if (!el.offsetParent || !el.textContent.trim()) return;" +
                "  var p = el.parentElement; if (!p) return;" +
                "  var k = p.className + '|' + p.id;" +
                "  if (!grupos[k]) grupos[k] = [];" +
                "  grupos[k].push(el.textContent.trim().substring(0,80));" +
                "});" +
                "var melhor = [];" +
                "Object.values(grupos).forEach(function(g) {" +
                "  var vistos = new Set();" +
                "  var uniq = g.filter(function(t) {" +
                "    if (vistos.has(t)) return false; vistos.add(t); return true;" +
                "  });" +
                "  if (uniq.length >= 2 && uniq.length < 20 && uniq.length > melhor.length)" +
                "    melhor = uniq;" +
                "});" +
                "return melhor.length >= 2 ? melhor : [];"
            );
            if (jsResult != null && jsResult.size() >= 2) {
                System.out.println("[SCAN] Abas via JS heurística: " + jsResult);
                return jsResult;
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    /** Clica em uma aba pelo texto usando XPath case-insensitive. */
    private boolean clicarAbaPorTexto(String nomeAba) {
        String lower = nomeAba.toLowerCase().trim();
        String ci = "translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')";
        String[] xpaths = {
            "//*[" + ci + "='" + lower + "']",
            "//*[contains(" + ci + ",'" + lower + "')]"
        };
        for (String xp : xpaths) {
            try {
                List<WebElement> els = driver.findElements(By.xpath(xp));
                for (WebElement el : els) {
                    try {
                        if (el.isDisplayed()) {
                            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
                            System.out.println("[SCAN] Clicou na aba '" + nomeAba + "'.");
                            return true;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Verifica se os campos do formulário estão dentro de um iframe.
     * Se o documento principal não tiver campos interativos visíveis, tenta cada iframe
     * e muda o contexto do WebDriver para o frame que contiver campos.
     * Deve ser chamado ANTES de escanearCampos().
     */
    private void switchToFormIframe() {
        try {
            String sel = "input:not([type=hidden]):not([type=submit]):not([type=button]):not([readonly]):not([disabled])," +
                         "select:not([disabled]),textarea:not([readonly]):not([disabled])";
            long visible = driver.findElements(By.cssSelector(sel)).stream()
                .filter(e -> { try { return e.isDisplayed(); } catch (Exception ex) { return false; } })
                .count();
            if (visible > 0) {
                System.out.println("[SCAN] " + visible + " campo(s) visível(eis) no documento principal.");
                return;
            }

            List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
            System.out.println("[SCAN] Nenhum campo no documento principal — verificando " + iframes.size() + " iframe(s)...");
            for (int i = 0; i < iframes.size(); i++) {
                try {
                    driver.switchTo().frame(i);
                    long frameVisible = driver.findElements(By.cssSelector(sel)).stream()
                        .filter(e -> { try { return e.isDisplayed(); } catch (Exception ex) { return false; } })
                        .count();
                    if (frameVisible > 0) {
                        System.out.println("[SCAN] Iframe " + (i + 1) + " tem " + frameVisible + " campo(s) — usando este frame para o scan.");
                        return; // permanece no frame
                    }
                    driver.switchTo().defaultContent();
                } catch (Exception e) {
                    System.out.println("[SCAN] Iframe " + (i + 1) + " inacessível: " + e.getMessage());
                    try { driver.switchTo().defaultContent(); } catch (Exception ignored) {}
                }
            }
            System.out.println("[SCAN] Nenhum campo encontrado em iframes — mantendo contexto principal.");
        } catch (Exception e) {
            System.out.println("[SCAN] Erro ao verificar iframes: " + e.getMessage());
        }
    }

    /**
     * Fecha o modal "Verificar Aluno/Candidato já Cadastrado" (panelCpf) se estiver aberto,
     * para não contaminar o escaneamento dos campos do formulário principal.
     */
    private void fecharModalVerificacao() {
        try {
            // Procura botão de fechar (X) ou "VOLTAR TELA CONSULTA" no modal
            List<WebElement> btnsFechar = driver.findElements(By.xpath(
                "//*[contains(@class,'rf-pp-hdr-cls') or contains(@class,'ui-dialog-titlebar-close')]"));
            for (WebElement btn : btnsFechar) {
                if (btn.isDisplayed()) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                    Thread.sleep(400);
                    System.out.println("[SCAN] Modal de verificação fechado pelo botão X.");
                    return;
                }
            }
            // Procura botão "VOLTAR TELA CONSULTA" específico do SEI
            List<WebElement> btnsVoltar = driver.findElements(By.xpath(
                "//*[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'VOLTAR TELA CONSULTA')]"));
            for (WebElement btn : btnsVoltar) {
                if (btn.isDisplayed()) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                    Thread.sleep(400);
                    System.out.println("[SCAN] Modal fechado via 'VOLTAR TELA CONSULTA'.");
                    return;
                }
            }
        } catch (Exception ignored) {}
    }


    /**
     * Para cada select na lista, itera as opções e detecta campos condicionais
     * (ex: "Nivel Educacional" muda os campos do cursoForm).
     *
     * Efeitos colaterais:
     *  - Modifica jaEscaneados: reordena opcoes do select para deixar a melhor opção em index 0.
     *  - Preenche idsCondicionaisOut com os IDs dos campos condicionais detectados.
     *  - Deixa os selects no DOM na opção que revela mais campos (para o re-scan posterior).
     *
     * Limita-se a selects com <= 15 opções para evitar scan demorado.
     */
    private void escanearCamposCondicionais(List<CampoDetectado> jaEscaneados,
                                             Set<String> idsCondicionaisOut) {
        List<CampoDetectado> novos = new ArrayList<>(); // rastreamento interno
        Set<String> idsVistos = new HashSet<>();
        for (CampoDetectado c : jaEscaneados) idsVistos.add(c.id + "|" + c.name);

        for (CampoDetectado selectCampo : new ArrayList<>(jaEscaneados)) {
            if (!"selecionar".equals(selectCampo.tipo)) continue;
            if (selectCampo.opcoes == null || selectCampo.opcoes.size() <= 1) continue;
            if (selectCampo.opcoes.size() > 15) {
                System.out.println("[SCAN] Select '" + selectCampo.label + "' tem " + selectCampo.opcoes.size() + " opções — pulando scan condicional.");
                continue;
            }

            System.out.println("[SCAN] Testando " + selectCampo.opcoes.size() + " opções do select '" + selectCampo.label + "' para campos condicionais...");

            int maxCamposVisiveis = 0;
            String melhorOpcao = selectCampo.opcoes.get(0);
            int novosTotal = 0;

            for (String opcao : selectCampo.opcoes) {
                try {
                    // Encontra o select no DOM
                    WebElement sel = null;
                    for (String loc : selectCampo.localizadores) {
                        try {
                            List<WebElement> els = driver.findElements(By.cssSelector(loc));
                            for (WebElement e : els) { if (e.isDisplayed()) { sel = e; break; } }
                            if (sel != null) break;
                        } catch (Exception ignored) {}
                    }
                    if (sel == null) break;

                    new Select(sel).selectByVisibleText(opcao);
                    Thread.sleep(150); // aguarda AJAX re-render

                    // Escaneia campos visíveis agora
                    List<CampoDetectado> visiveisAgora = escanearCampos();
                    int novosNaOpcao = 0;
                    for (CampoDetectado c : visiveisAgora) {
                        String key = c.id + "|" + c.name;
                        if (!idsVistos.contains(key)) {
                            c.condicionalSelectLabel = selectCampo.label;
                            c.condicionalSelectValor = opcao;
                            novos.add(c);
                            idsVistos.add(key);
                            novosNaOpcao++;
                            novosTotal++;
                        }
                    }

                    // Rastreia qual opção revela mais campos (para usar como padrão)
                    int totalVisiveis = visiveisAgora.size();
                    if (totalVisiveis > maxCamposVisiveis) {
                        maxCamposVisiveis = totalVisiveis;
                        melhorOpcao = opcao;
                    }

                    if (novosNaOpcao > 0)
                        System.out.println("[SCAN]   Opção '" + opcao + "': " + novosNaOpcao + " campo(s) novo(s), total visível=" + totalVisiveis);
                } catch (Exception e) {
                    System.out.println("[SCAN] Erro ao testar opção '" + opcao + "' do select '" + selectCampo.label + "': " + e.getMessage());
                }
            }

            // Promove a melhor opção para index 0 — gerarValorTeste() usa opcoes.get(0)
            // Isso garante que o executor selecione o valor que revela mais campos
            if (!melhorOpcao.equals(selectCampo.opcoes.get(0))) {
                selectCampo.opcoes.remove(melhorOpcao);
                selectCampo.opcoes.add(0, melhorOpcao);
                System.out.println("[SCAN] Select '" + selectCampo.label + "': melhor opção promovida = '" + melhorOpcao + "'");
            }

            // Registra IDs condicionais no Set de saída
            for (CampoDetectado c : novos) idsCondicionaisOut.add(c.id + "|" + c.name);

            // Deixa o select na melhor opção para o re-scan posterior
            try {
                WebElement sel = null;
                for (String loc : selectCampo.localizadores) {
                    try {
                        List<WebElement> els = driver.findElements(By.cssSelector(loc));
                        for (WebElement e : els) { if (e.isDisplayed()) { sel = e; break; } }
                        if (sel != null) break;
                    } catch (Exception ignored) {}
                }
                if (sel != null) {
                    new Select(sel).selectByVisibleText(melhorOpcao);
                    Thread.sleep(150);
                }
            } catch (Exception ignored) {}

            if (novosTotal > 0)
                System.out.println("[SCAN] Select '" + selectCampo.label + "': " + novosTotal + " campo(s) condicional(is) no total.");
        }
        // void — efeitos colaterais: seletor ótimo nos selects, idsCondicionaisOut preenchido
    }

    /**
     * Escaneia campos visíveis em UMA única chamada JavaScript.
     * Substitui o loop anterior que fazia N×10+ round trips WebDriver (um por atributo por elemento).
     * Para 15 campos: antes ~150 chamadas, agora 1.
     */
    @SuppressWarnings("unchecked")
    private List<CampoDetectado> escanearCampos() {
        List<CampoDetectado> campos = new ArrayList<>();

        List<Map<String, Object>> dados;
        try {
            dados = (List<Map<String, Object>>) ((JavascriptExecutor) driver).executeScript(
                // IDs de popups reais do SEI — NÃO usar classes (rf-pp-cnt pode ser o container principal do form)
                "var MI=['panelCpf','panelVerifica','panelImportar','panelAluno','panelCandidato'];" +
                "var MK=['__/__/____','__/__/__ __:__','__:__','dd/mm/aaaa','dd/mm/yyyy','mm/aaaa','___.___.___-__','__.___.___/____-__','(__) _____-____'];" +
                "function inModal(el){" +
                // Verifica se o elemento está dentro de um popup OCULTO (display:none no ancestral com rf-pp)
                // OU dentro de um popup real pelo ID
                "  var p=el.parentElement,d=0;while(p&&d<10){" +
                "    var id=p.id||'';" +
                "    for(var i=0;i<MI.length;i++)if(id.indexOf(MI[i])>=0)return true;" +
                "    p=p.parentElement;d++;}return false;}" +
                "function findLbl(el){" +
                "  var lbl='';" +
                // Estratégia 1: label[for=id] — mais confiável
                "  if(el.id){try{var l=document.querySelector('label[for=\"'+el.id+'\"]');" +
                "    if(l)lbl=l.innerText.trim().replace(/[*:]/g,'').trim();}catch(e){}}" +
                // Estratégia 2: SEI usa <td> anterior como label (<tr><td>Label</td><td><input/></td></tr>)
                // NÃO sobe além do <tr> para não pegar labels de outros campos
                "  if(!lbl){var td=el;while(td&&td.tagName!=='TD'&&td.tagName!=='TH'&&td.tagName!=='TR')td=td.parentElement;" +
                "    if(td&&(td.tagName==='TD'||td.tagName==='TH')){var prev=td.previousElementSibling;" +
                "      if(prev){var ls=prev.querySelectorAll('label');if(ls.length)lbl=ls[0].innerText.trim().replace(/[*:]/g,'').trim();" +
                "        if(!lbl){var t=prev.innerText.trim().replace(/[*:]/g,'').trim();if(t&&t.length<60)lbl=t;}}}}" +
                // Estratégia 3: label no mesmo container — sobe até 3 níveis (Bootstrap/PrimeFaces colocam label fora do parent direto)
                "  if(!lbl){var ac=el.parentElement;for(var lv=0;ac&&lv<3&&!lbl;lv++,ac=ac.parentElement){" +
                "    if(['BODY','HTML','FORM'].indexOf(ac.tagName||'')>=0)break;" +
                "    var ls=ac.querySelectorAll('label');" +
                "    for(var i=0;i<ls.length&&!lbl;i++){var t=ls[i].innerText.trim().replace(/[*:]/g,'').trim();if(t&&t.length<60&&t.length>1)lbl=t;}" +
                "  }}" +
                // Estratégia 4: aria-label, title, placeholder
                "  if(!lbl)lbl=(el.getAttribute('aria-label')||el.getAttribute('title')||'').replace(/[*:]/g,'').trim();" +
                "  if(!lbl){var ph=(el.getAttribute('placeholder')||'').trim();" +
                "    var skip=false;for(var i=0;i<MK.length;i++)if(ph===MK[i]){skip=true;break;}" +
                "    if(!skip)lbl=ph;}" +
                // Estratégia 5: subir no DOM procurando texto próximo (div/li/span antes do campo)
                "  if(!lbl){var sib=el.previousElementSibling;var d=0;" +
                "    while(sib&&!lbl&&d<3){" +
                "      var t=(sib.innerText||'').trim().replace(/[*:]/g,'').trim();" +
                "      if(t&&t.length<60&&t.length>1)lbl=t;" +
                "      sib=sib.previousElementSibling;d++;}}" +
                "  if(!lbl){var p2=el.parentElement;var d2=0;" +
                "    while(p2&&!lbl&&d2<4){" +
                "      var sib2=p2.previousElementSibling;" +
                "      if(sib2){var t2=(sib2.innerText||'').trim().replace(/[*:]/g,'').trim();" +
                "        if(t2&&t2.length<60&&t2.length>1)lbl=t2;}" +
                "      p2=p2.parentElement;d2++;}}" +
                // Estratégia 6 (fallback final): derivar label do ID ou name
                // Funciona com camelCase (nomeDocumentacao → "Nome Documentacao")
                // E com ids minúsculos (descricao → "Descricao", situacao → "Situacao")
                "  if(!lbl){var idn=el.id||el.name||'';if(idn){var pt=idn;var ci=pt.lastIndexOf(':');" +
                "    if(ci>=0)pt=pt.substring(ci+1);" +
                "    pt=pt.replace(/^(input|txt|fld|campo|field)/,'').replace(/_/g,' ');" +
                "    var sp=pt.replace(/([a-z])([A-Z])/g,'$1 $2').trim();" +
                "    if(sp&&sp.length>=3&&!/^j_idt/.test(pt))lbl=sp.charAt(0).toUpperCase()+sp.slice(1);}}" +
                "  return lbl.replace(/[*:]/g,'').trim();}" +
                "var sel=\"input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=checkbox]):not([type=radio]),select,textarea\";" +
                "return Array.from(document.querySelectorAll(sel)).filter(function(el){" +
                // getClientRects().length > 0 é mais confiável que offsetParent:
                // funciona para position:fixed (offsetParent seria null mas campo está visível)
                // e para elementos dentro de ancestors display:none (retorna 0 rects)
                "  return el.getClientRects().length>0&&!el.readOnly&&!el.disabled&&!inModal(el);" +
                "}).map(function(el){" +
                "  var opts=[];" +
                "  if(el.tagName==='SELECT')for(var i=0;i<el.options.length;i++){" +
                "    var t=el.options[i].text.trim();" +
                "    if(t&&t!=='Selecione'&&t.indexOf('--')!==0)opts.push(t);}" +
                "  return{id:el.id||'',name:el.name||'',tag:el.tagName.toLowerCase()," +
                "    type:el.getAttribute('type')||'',lbl:findLbl(el),opts:opts};});");
        } catch (Exception e) {
            System.out.println("[SCAN] Erro JS scan: " + e.getMessage());
            return campos;
        }

        if (dados == null) return campos;
        Set<String> vistos = new HashSet<>();

        for (Map<String, Object> d : dados) {
            try {
                String id      = (String) d.get("id");
                String name    = (String) d.get("name");
                String tagName = (String) d.get("tag");
                String type    = (String) d.get("type");
                String label   = (String) d.get("lbl");
                List<String> opts = (List<String>) d.get("opts");

                if (id == null) id = "";
                if (name == null) name = "";
                if (tagName == null) tagName = "input";

                String key = id + "|" + name;
                if (vistos.contains(key)) continue;
                vistos.add(key);

                if (label == null || label.isEmpty() || label.length() > 60) continue;
                if (ehMascaraInput(label)) continue;

                CampoDetectado campo = new CampoDetectado();
                campo.label = label;
                campo.id = id;
                campo.name = name;
                campo.tipo = "select".equals(tagName) ? "selecionar" : "preencher";
                campo.elementType = tagName;
                campo.ehData = detectarCampoDataSimples(id, name, label, type);

                campo.localizadores = new ArrayList<>();
                if (!id.isEmpty()) {
                    campo.localizadores.add("#" + escapeId(id));
                    campo.localizadores.add(tagName + "[id='" + id + "']");
                    String lastPart = id.contains(":") ? id.substring(id.lastIndexOf(":") + 1) : id;
                    if (!lastPart.isEmpty() && !lastPart.equals(id))
                        campo.localizadores.add(tagName + "[id*='" + lastPart + "']");
                }
                if (!name.isEmpty()) campo.localizadores.add(tagName + "[name='" + name + "']");

                if ("select".equals(tagName) && opts != null && !opts.isEmpty())
                    campo.opcoes = new ArrayList<>(opts);

                campos.add(campo);
            } catch (Exception ignored) {}
        }

        return campos;
    }

    private boolean detectarCampoDataSimples(String id, String name, String label, String type) {
        if ("date".equals(type) || "datetime-local".equals(type)) return true;
        String lower = id.toLowerCase() + "|" + name.toLowerCase() + "|" + label.toLowerCase();
        return lower.contains("data") || lower.contains("nascimento") || lower.contains("expedicao")
            || lower.contains("vencimento") || lower.contains("expedição") || lower.contains("emissao");
    }

    private boolean ehMascaraInput(String txt) {
        if (PLACEHOLDERS_MASCARAS.contains(txt.trim().toLowerCase())) return true;
        // Detecta padrões com underscores e separadores (ex: __/__/____)
        String sem = txt.replaceAll("[/_\\-.]", "").trim();
        return !sem.isEmpty() && sem.chars().allMatch(c -> c == '_' || c == ' ');
    }

    private List<BotaoDetectado> escanearBotoes() {
        List<BotaoDetectado> botoes = new ArrayList<>();
        // Captura todos os botões: <button>, <input submit/button> e <a> que funcionam como botão
        // a.btn        = Bootstrap btn class (otm:commandButton renderiza como <a class="btn btn-dark btn-gravar">)
        // a[role=button]         = ARIA buttons
        // a[onclick*=RichFaces]  = botões AJAX sem classe btn
        // a[id*=btn]             = convenção de id legada
        List<WebElement> elementos = driver.findElements(
            By.cssSelector("button:not([style*='display:none']), input[type='submit'], input[type='button'], " +
                "a.btn, a[role='button'], a[onclick*='RichFaces.ajax'], a[id*='btn']"));

        Set<String> idsVistos = new HashSet<>();
        for (WebElement el : elementos) {
            try {
                if (!el.isDisplayed()) continue;
                String id = el.getAttribute("id");
                if (id != null && idsVistos.contains(id)) continue; // evita duplicatas
                if (id != null && !id.isEmpty()) idsVistos.add(id);

                String tag = el.getTagName();
                String txt = el.getText().trim();
                String val = el.getAttribute("value");
                String cls = el.getAttribute("class");
                String texto = !txt.isEmpty() ? txt : val;
                if (texto == null || texto.isEmpty()) continue;

                BotaoDetectado btn = new BotaoDetectado();
                btn.texto = texto;
                btn.id = id;
                btn.localizadores = new ArrayList<>();

                // 1. ID exato (melhor localizador)
                if (id != null && !id.isEmpty()) {
                    btn.localizadores.add("#" + escapeId(id));
                    // Parte final do id composto (ex: form:salvar:salvar → salvar)
                    String lastPart = id.contains(":") ? id.substring(id.lastIndexOf(":") + 1) : id;
                    if (!lastPart.equals(id) && lastPart.length() > 2)
                        btn.localizadores.add(tag + "[id*='" + lastPart + "']");
                }

                // 2. Classe de ação específica (btn-gravar, btn-novo, btn-excluir, etc.)
                if (cls != null) {
                    for (String c : cls.split("\\s+")) {
                        if (c.startsWith("btn-") && !c.equals("btn-group") && !c.equals("btn-lg")
                                && !c.equals("btn-sm") && !c.equals("btn-xs") && !c.equals("btn-focus")
                                && !c.equals("btn-block") && !c.equals("btn-outline")) {
                            btn.localizadores.add(tag + "." + c);
                            break;
                        }
                    }
                }

                // 3. value para inputs
                if ("input".equals(tag) && val != null && !val.isEmpty())
                    btn.localizadores.add("input[value='" + val + "']");

                // 4. XPath por texto (funciona mesmo sem id/classe, e com texto composto por SVG+texto)
                String textoSafe = texto.replace("'", "\\'");
                btn.localizadores.add("//" + tag + "[contains(normalize-space(.), '" + textoSafe + "')]");

                botoes.add(btn);
            } catch (StaleElementReferenceException ignored) {}
        }

        return botoes;
    }

    private void gerarCenario(String id, String descricao, String modulo, String tela, String telaForm,
                               List<CampoDetectado> campos, List<BotaoDetectado> botoes, String saida) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();

            root.put("id", id);
            root.put("descricao", descricao);
            root.put("modulo", modulo);
            root.put("tela", tela);
            if (telaForm != null && !telaForm.equals(tela)) root.put("tela_form", telaForm);
            root.put("_gerado_automaticamente", true);

            ArrayNode opsDisp = root.putArray("operacoes_disponiveis");
            opsDisp.add("inserir"); opsDisp.add("alterar"); opsDisp.add("excluir"); opsDisp.add("ortografia");

            // Auto-detecta campo_chave: primeiro campo de texto não-data
            String campoChave = "";
            for (CampoDetectado c : campos) {
                if (c.tipo.equals("preencher") && !c.ehData) {
                    campoChave = normalizar(c.label);
                    break;
                }
            }
            if (!campoChave.isEmpty()) {
                root.put("campo_chave", campoChave);
                System.out.println("[SCAN] campo_chave auto-detectado: " + campoChave);
            }

            // dados_teste — valores fictícios inteligentes
            ObjectNode dadosTeste = root.putObject("dados_teste");
            Map<String, String> valoresMapa = new LinkedHashMap<>();
            for (CampoDetectado c : campos) {
                String key = normalizar(c.label);
                String valor = gerarValorTeste(c);
                valoresMapa.put(key, valor);
                dadosTeste.put(key, valor);
                if (c.tipo.equals("selecionar") && c.opcoes != null && !c.opcoes.isEmpty())
                    dadosTeste.put(key + "_opcoes_disponiveis", String.join(" | ", c.opcoes));
            }

            // ---- INSERIR ----
            ObjectNode inserir = root.putObject("inserir");
            ArrayNode acoesInserir = inserir.putArray("acoes");

            // Botão Novo primeiro
            BotaoDetectado btnNovo = encontrarBotao(botoes, "Novo", "Adicionar", "Incluir", "Cadastrar", "Inserir");
            if (btnNovo != null) {
                ObjectNode clique = acoesInserir.addObject();
                clique.put("tipo", "clicar");
                clique.put("label", "Novo");
                clique.put("texto_botao", btnNovo.texto);
                ArrayNode locs = clique.putArray("localizadores");
                for (String l : btnNovo.localizadores) locs.add(l);
                locs.add("#form\\:btnNovo"); locs.add("a[id*='btnNovo']");
            }

            // Preencher cada campo — com valor fictício já preenchido
            // Insere clicar_aba antes do primeiro campo de cada aba
            String abaAtual = null;
            for (CampoDetectado c : campos) {
                String campoKey = normalizar(c.label);
                String valor = valoresMapa.getOrDefault(campoKey, "");

                // Clica na aba quando muda
                if (c.aba != null && !c.aba.equals(abaAtual)) {
                    abaAtual = c.aba;
                    ObjectNode acoesAba = acoesInserir.addObject();
                    acoesAba.put("tipo", "clicar_aba");
                    acoesAba.put("label", c.aba);
                    acoesAba.put("valor", c.aba);
                    acoesAba.putArray("localizadores");
                }

                ObjectNode acao = acoesInserir.addObject();
                acao.put("tipo", c.tipo);
                acao.put("campo", campoKey);
                acao.put("label", c.label);
                acao.put("valor", valor);                         // VALOR JÁ PREENCHIDO
                if (c.ehData) acao.put("_tipo_campo", "data");   // hint para CenarioExecutor
                if (c.id != null) acao.put("_id_atual", c.id);
                if (c.isCondicional()) {
                    acao.put("condicional", true);
                    acao.put("_visivel_quando", c.condicionalSelectLabel + " = " + c.condicionalSelectValor);
                }
                ArrayNode locs = acao.putArray("localizadores");
                for (String l : c.localizadores) locs.add(l);
            }

            // Salvar inserir
            BotaoDetectado btnSalvar = encontrarBotao(botoes, "Salvar", "Gravar", "Confirmar", "OK");
            adicionarBotaoSalvar(acoesInserir, btnSalvar);

            ArrayNode valsInserir = inserir.putArray("validacoes");
            ObjectNode v1 = valsInserir.addObject();
            v1.put("tipo", "mensagem_sucesso");
            v1.put("valor", "sucesso");
            v1.put("_alternativas", "Ajuste para a mensagem real de sucesso do SEI (ex: 'salvo com sucesso', 'cadastrado')");

            // ---- ALTERAR ----
            ObjectNode alterar = root.putObject("alterar");
            ArrayNode acoesAlterar = alterar.putArray("acoes");

            int alterados = 0;
            String abaAtualAlterar = null;
            for (CampoDetectado c : campos) {
                if (alterados >= 2) break;
                if (c.tipo.equals("preencher") && !c.ehData) {
                    if (c.aba != null && !c.aba.equals(abaAtualAlterar)) {
                        abaAtualAlterar = c.aba;
                        ObjectNode abaAcao = acoesAlterar.addObject();
                        abaAcao.put("tipo", "clicar_aba");
                        abaAcao.put("label", c.aba);
                        abaAcao.put("valor", c.aba);
                        abaAcao.putArray("localizadores");
                    }
                    String campoKey = normalizar(c.label);
                    String valorOriginal = valoresMapa.getOrDefault(campoKey, "");
                    String valorAlt = gerarValorAlterado(c, valorOriginal);
                    ObjectNode acao = acoesAlterar.addObject();
                    acao.put("tipo", "preencher");
                    acao.put("campo", campoKey);
                    acao.put("label", c.label);
                    acao.put("valor", valorAlt);
                    acao.put("valor_alterar", valorAlt);
                    ArrayNode locs = acao.putArray("localizadores");
                    for (String l : c.localizadores) locs.add(l);
                    alterados++;
                }
            }

            adicionarBotaoSalvar(acoesAlterar, btnSalvar);

            ArrayNode valsAlt = alterar.putArray("validacoes");
            ObjectNode va = valsAlt.addObject();
            va.put("tipo", "mensagem_sucesso");
            va.put("valor", "sucesso");

            // ---- EXCLUIR ----
            ObjectNode excluir = root.putObject("excluir");
            ArrayNode acoesExcluir = excluir.putArray("acoes");

            BotaoDetectado btnExcluir = encontrarBotao(botoes, "Excluir", "Deletar", "Remover", "Delete");
            ObjectNode excluirAcao = acoesExcluir.addObject();
            excluirAcao.put("tipo", "clicar");
            excluirAcao.put("label", "Excluir");
            excluirAcao.put("texto_botao", btnExcluir != null ? btnExcluir.texto : "Excluir");
            ArrayNode excluirLocs = excluirAcao.putArray("localizadores");
            if (btnExcluir != null) for (String l : btnExcluir.localizadores) excluirLocs.add(l);
            excluirLocs.add("#form\\:btnExcluir"); excluirLocs.add("a[id*='btnExcluir']");
            excluirLocs.add("button[id*='excluir']"); excluirLocs.add("input[value='Excluir']");

            ObjectNode confirmAcao = acoesExcluir.addObject();
            confirmAcao.put("tipo", "confirmar_dialogo");
            confirmAcao.put("label", "Confirmar exclusão");
            confirmAcao.putArray("localizadores");

            ArrayNode valsExc = excluir.putArray("validacoes");
            ObjectNode ve = valsExc.addObject();
            ve.put("tipo", "mensagem_sucesso");
            ve.put("valor", "excluído");
            ve.put("_alternativas", "Ajuste para a mensagem real do SEI");

            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(saida), root);

        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao gerar cenário: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void adicionarBotaoSalvar(ArrayNode acoes, BotaoDetectado btnSalvar) {
        ObjectNode acao = acoes.addObject();
        acao.put("tipo", "clicar");
        acao.put("label", "Salvar");
        // texto_botao: usa o texto real detectado (pode ser "GRAVAR") ou fallback
        String textoReal = btnSalvar != null ? btnSalvar.texto : "Salvar";
        acao.put("texto_botao", textoReal);
        ArrayNode locs = acao.putArray("localizadores");
        if (btnSalvar != null) for (String l : btnSalvar.localizadores) locs.add(l);
        // Seletores comuns em SEI (otm:commandButton renderiza como <a id="form:salvar:salvar">)
        locs.add("a[id*='salvar']"); locs.add("button[id*='salvar']");
        locs.add("a.btn-gravar"); locs.add("a[class*='btn-gravar']");
        locs.add("#form\\:btnSalvar"); locs.add("input[value='Salvar']");
        locs.add("input[value='GRAVAR']"); locs.add("input[type='submit']");
    }

    /**
     * Gera valor fictício inteligente com base no label/id do campo.
     */
    private String gerarValorTeste(CampoDetectado c) {
        String label = c.label.toLowerCase();
        String id = (c.id != null ? c.id : "").toLowerCase();
        String ref = label + "|" + id;

        if (c.tipo.equals("selecionar") && c.opcoes != null && !c.opcoes.isEmpty()) {
            // Valores padrão para campos conhecidos — escolhe dentro das opções disponíveis
            String valorPreferido = escolherValorPreferido(ref, c.opcoes);
            return valorPreferido != null ? valorPreferido : c.opcoes.get(0);
        }

        // Campos de data
        if (c.ehData || ref.contains("nascimento") || ref.contains("expedicao") || ref.contains("expedição")
                || ref.contains("emissao") || ref.contains("vencimento")) return "01/01/1990";
        if (ref.contains("data") || ref.contains("/dt_")) return "01/01/2024";

        // Documentos
        if (ref.contains("cpf")) return gerarCpfValido();
        if (ref.contains("cnpj")) return "11.222.333/0001-44";
        if (ref.contains("rg") || ref.contains("identidade")) return "1234567";
        if (ref.contains("pis") || ref.contains("nit")) return "123.45678.90-1";
        if (ref.contains("titulo") && ref.contains("eleitor")) return "123456789012";
        if (ref.contains("passaporte")) return "AB123456";
        if (ref.contains("cnh") || ref.contains("habilitacao")) return "12345678901";

        // Contato
        if (ref.contains("email") || ref.contains("e-mail")) return "teste.qa@otimize.com.br";
        if (ref.contains("celular") || ref.contains("fax")) return "(62) 99999-0001";
        if (ref.contains("telefone") || ref.contains("fone") || ref.contains("tel")) return "(62) 3333-0000";

        // Endereço
        if (ref.contains("cep")) return "74000-000";
        if (ref.contains("logradouro") || ref.contains("endereco") || ref.contains("endereço") || ref.contains("rua")) return "Rua Teste QA";
        if (ref.contains("numero") || ref.contains("número") || ref.contains("num")) return "100";
        if (ref.contains("complemento") || ref.contains("apto")) return "Apto 01";
        if (ref.contains("bairro")) return "Centro";
        if (ref.contains("cidade") || ref.contains("municipio") || ref.contains("município")) return "Goiânia";
        if (ref.contains("estado") || ref.contains("uf")) return "GO";
        if (ref.contains("pais") || ref.contains("país")) return "Brasil";

        // Acadêmico
        if (ref.contains("matricula") || ref.contains("matrícula")) return "2024001";
        if (ref.contains("ra") && label.length() <= 3) return "2024001";
        if (ref.contains("ano") && ref.contains("letivo")) return "2026";
        if (ref.contains("semestre") || ref.contains("periodo") || ref.contains("período")) return "1";
        if (ref.contains("vagas") || ref.contains("quantidade") || ref.contains("qtd") || ref.contains("capacidade")) return "30";
        if (ref.contains("carga") && ref.contains("horaria")) return "40";
        if (ref.contains("carga") || ref.contains("hora")) return "40";

        // Financeiro
        if (ref.contains("valor") || ref.contains("preço") || ref.contains("preco") || ref.contains("custo")) return "100,00";
        if (ref.contains("desconto")) return "10,00";
        if (ref.contains("parcela")) return "12";

        // Nome / descrição genérica — único via timestamp
        if (ref.contains("nome") || ref.contains("descricao") || ref.contains("descrição")) return "TESTE QA {{timestamp}}";
        if (ref.contains("observacao") || ref.contains("observação") || ref.contains("obs")) return "Observação gerada pelo Otimize Qualidade";

        return "TESTE QA {{timestamp}}";
    }

    /**
     * Gera um CPF matematicamente válido baseado no timestamp atual.
     * Formato: 000.000.000-00
     * O CPF gerado passa no algoritmo de validação dos dígitos verificadores.
     */
    private String gerarCpfValido() {
        // Usa os últimos 9 dígitos do timestamp para ter CPFs variados a cada scan
        long base = System.currentTimeMillis() % 1_000_000_000L;
        // Garante que não seja sequência inválida (todos iguais: 111.111.111-11)
        base = base % 999_999_999L + 100_000_000L; // garante 9 dígitos

        int[] d = new int[11];
        String s = String.format("%09d", base % 1_000_000_000L);
        for (int i = 0; i < 9; i++) d[i] = s.charAt(i) - '0';

        // Dígito verificador 1
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += d[i] * (10 - i);
        int r = sum % 11;
        d[9] = r < 2 ? 0 : 11 - r;

        // Dígito verificador 2
        sum = 0;
        for (int i = 0; i < 10; i++) sum += d[i] * (11 - i);
        r = sum % 11;
        d[10] = r < 2 ? 0 : 11 - r;

        return String.format("%d%d%d.%d%d%d.%d%d%d-%d%d",
            d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[10]);
    }

    /**
     * Retorna valor preferido para selects com semântica conhecida.
     * Se o valor preferido estiver na lista de opções, usa-o.
     * Caso contrário retorna null (usa opcoes.get(0) como fallback).
     */
    private String escolherValorPreferido(String ref, List<String> opcoes) {
        // Mapa: padrão no ref → valor preferido
        // Ordem importa: mais específico primeiro
        String[][] preferencias = {
            // Nível Educacional → Graduação (revela campos de titulação para diploma)
            { "niveleducacional|nivel_educacional|nivelEducacional", "Graduação" },
            // Sexo/Gênero → Masculino
            { "sexo|genero|gênero", "Masculino" },
            // Estado civil → Solteiro
            { "estadocivil|estado_civil", "Solteiro" },
            // Situação → Ativo/Ativa
            { "situacao|situação|status", "Ativo" },
            // Turno → Matutino
            { "turno", "Matutino" },
            // Tipo → Presencial
            { "tipocurso|tipo_curso", "Presencial" },
            // Período → 1
            { "periodo|período", "1" },
        };

        for (String[] par : preferencias) {
            // Verifica se algum dos padrões do par existe no ref
            for (String padrao : par[0].split("\\|")) {
                if (ref.replace("_", "").replace(" ", "").toLowerCase().contains(padrao.replace("_", "").toLowerCase())) {
                    String valorAlvo = par[1];
                    // 1º: match exato (case-insensitive) — evita "Graduação Tecnológica" ao buscar "Graduação"
                    for (String opcao : opcoes) {
                        if (opcao.trim().equalsIgnoreCase(valorAlvo)) return opcao;
                    }
                    // 2º: match exato por palavra inteira (começa com o valor)
                    for (String opcao : opcoes) {
                        if (opcao.trim().toLowerCase().startsWith(valorAlvo.toLowerCase() + " ") ||
                            opcao.trim().equalsIgnoreCase(valorAlvo)) return opcao;
                    }
                    break; // padrão bateu mas valor não está na lista → usa fallback
                }
            }
        }
        return null; // sem preferência → usa opcoes.get(0)
    }

    /** Gera valor alternativo para o alterar (diferente do inserir) */
    private String gerarValorAlterado(CampoDetectado c, String valorOriginal) {
        if (valorOriginal.contains("TESTE QA")) return valorOriginal.replace("TESTE QA", "EDITADO QA");
        if (valorOriginal.matches("\\d+")) return String.valueOf(Integer.parseInt(valorOriginal) + 1);
        return valorOriginal + " EDITADO";
    }

    private BotaoDetectado encontrarBotao(List<BotaoDetectado> botoes, String... textos) {
        for (String texto : textos)
            for (BotaoDetectado b : botoes)
                if (b.texto != null && b.texto.equalsIgnoreCase(texto)) return b;
        for (String texto : textos)
            for (BotaoDetectado b : botoes)
                if (b.texto != null && b.texto.toLowerCase().contains(texto.toLowerCase())) return b;
        return null;
    }

    private String normalizar(String label) {
        return label.toLowerCase()
            .replaceAll("[^a-z0-9 ]", "")
            .trim()
            .replaceAll("\\s+", "_");
    }

    private String escapeId(String id) {
        return id.replace(":", "\\:").replace(".", "\\.");
    }

    // Inner data classes
    static class CampoDetectado {
        String label;
        String id;
        String name;
        String tipo;
        String elementType;
        boolean ehData;
        List<String> localizadores;
        List<String> opcoes;
        /** Aba onde este campo está (null = aba inicial) */
        String aba;
        /** Label do select que precisa ser definido para este campo aparecer (null = sempre visível) */
        String condicionalSelectLabel;
        /** Valor do select que torna este campo visível */
        String condicionalSelectValor;

        boolean isCondicional() { return condicionalSelectLabel != null; }

        @Override
        public String toString() {
            String extra = (aba != null ? " aba='" + aba + "'" : "")
                + (condicionalSelectLabel != null ? " quando='" + condicionalSelectLabel + "'='" + condicionalSelectValor + "'" : "");
            return String.format("[%s%s] label='%s' id='%s'%s",
                tipo, ehData ? "/data" : "", label, id, extra);
        }
    }

    static class BotaoDetectado {
        String texto;
        String id;
        List<String> localizadores;

        @Override
        public String toString() {
            return String.format("Botão texto='%s' id='%s'", texto, id);
        }
    }
}
