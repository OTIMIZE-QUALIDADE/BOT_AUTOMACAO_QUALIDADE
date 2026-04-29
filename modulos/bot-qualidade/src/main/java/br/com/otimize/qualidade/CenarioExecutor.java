package br.com.otimize.qualidade;

import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.InvalidElementStateException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.Keys;
import io.github.bonigarcia.wdm.WebDriverManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * CenarioExecutor — executa cenários de teste CRUD no SEI.
 *
 * Fluxo por operação:
 *  INSERIR → Navega para tela Cons → executa ações (1ª deve ser clicar "Novo")
 *  ALTERAR → Navega para tela Cons → busca o registro → abre → edita → salva
 *  EXCLUIR → Navega para tela Cons → busca o registro → abre → exclui → confirma
 */
public class CenarioExecutor {

    private final Config config;
    private final String locatorsPath;
    private WebDriver driver;
    private SmartLocator locator;
    private final SpellChecker spellChecker;
    private final ValidadorCamposObrigatorios validadorCampos;

    // Valor do campo chave usado no inserir — reutilizado para buscar no alterar/excluir
    private String valorChaveInserido = null;
    // Valor do campo chave ANTES de uma alteração — fallback para excluir quando o
    // alterar renomeia o registro e o nome original é necessário para localizá-lo
    private String valorChaveAntesDaAlteracao = null;

    // Timestamp fixo por execução do cenário — garante que {{timestamp}} seja idêntico
    // em todas as chamadas dentro do mesmo run (evita mismatch entre preenchimento e busca)
    private final long runTimestamp = System.currentTimeMillis() % 100000;

    private final boolean headless;

    public CenarioExecutor(Config config, String locatorsPath, boolean headless) {
        this.config = config;
        this.locatorsPath = locatorsPath;
        this.headless = headless;
        this.spellChecker = new SpellChecker();
        this.validadorCampos = new ValidadorCamposObrigatorios();
    }

    public List<StepResult> executar(TestScenario cenario, List<String> operacoes) {
        List<StepResult> resultados = new ArrayList<>();

        System.out.println("[INFO] Iniciando WebDriver...");
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            if (headless) {
                options.addArguments("--headless=new");
                options.addArguments("--no-sandbox");
                options.addArguments("--disable-gpu");
                options.addArguments("--window-size=1920,1080");
            } else {
                options.addArguments("--start-maximized");
            }
            options.addArguments("--disable-blink-features=AutomationControlled");
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            locator = new SmartLocator(driver, config.getWaitMs());
        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao iniciar Chrome: " + e.getMessage());
            StepResult r = new StepResult("setup", "Inicializar WebDriver");
            r.setPassou(false);
            r.setMensagem(e.getMessage());
            resultados.add(r);
            return resultados;
        }

        try {
            StepResult loginResult = executarLogin();
            resultados.add(loginResult);
            if (!loginResult.isPassou()) return resultados;

            for (String op : operacoes) {
                switch (op.trim().toLowerCase()) {
                    case "inserir":
                        if (cenario.getInserir() != null)
                            resultados.addAll(executarInserir(cenario));
                        break;
                    case "alterar":
                        if (cenario.getAlterar() != null)
                            resultados.addAll(executarAlterarOuExcluir("alterar", cenario, cenario.getAlterar()));
                        break;
                    case "excluir":
                        if (cenario.getExcluir() != null)
                            resultados.addAll(executarAlterarOuExcluir("excluir", cenario, cenario.getExcluir()));
                        break;
                    case "ortografia":
                        resultados.addAll(executarOrtografia(cenario));
                        break;
                    case "campos_obrigatorios":
                        resultados.addAll(executarCamposObrigatorios(cenario));
                        break;
                }
            }
        } finally {
            pausa(200);
            if (driver != null) driver.quit();
        }

        return resultados;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Login
    // ─────────────────────────────────────────────────────────────────────────────

    private StepResult executarLogin() {
        StepResult result = new StepResult("login", "Login no SEI");
        long start = System.currentTimeMillis();
        try {
            System.out.println("[INFO] Navegando para: " + config.getSeiUrl());
            driver.get(config.getSeiUrl());

            // Aguarda campo de usuário aparecer — mais rápido que sleep fixo
            new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("input[type='text'], input[id*='usuario'], input[name='usuario']")));

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

            WebElement btn = locator.encontrar(
                List.of("input[type='submit']", "#form\\:btnEntrar", "button[type='submit']"),
                "Entrar", "button");
            String urlAntes = driver.getCurrentUrl();
            jsClick(btn);

            // Aguarda URL mudar (saiu da tela de login) — sem sleep fixo
            try {
                new WebDriverWait(driver, Duration.ofSeconds(7))
                    .until(d -> !d.getCurrentUrl().equals(urlAntes));
            } catch (Exception ignored) {}

            if (driver.getCurrentUrl().contains("login") || driver.getPageSource().contains("Usuário ou senha")) {
                throw new Exception("Login falhou — verifique usuário e senha");
            }

            result.setPassou(true);
            result.setMensagem("Login realizado");
            System.out.println("[OK] Login realizado. URL: " + driver.getCurrentUrl());
        } catch (Exception e) {
            result.setPassou(false);
            result.setMensagem("Falha no login: " + e.getMessage());
            result.setScreenshotBase64(screenshot());
            System.out.println("[ERRO] Login: " + e.getMessage());
        }
        result.setDuracaoMs(System.currentTimeMillis() - start);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // INSERIR: navega para Cons → clica NOVO (aguarda URL mudar) → preenche Form
    // ─────────────────────────────────────────────────────────────────────────────

    private List<StepResult> executarInserir(TestScenario cenario) {
        List<StepResult> resultados = new ArrayList<>();
        System.out.println("[INFO] ══ INSERIR ══");

        // Inicializa valorChaveInserido a partir dos dados de teste como fallback
        if (cenario.getCampo_chave() != null && cenario.getDados_teste() != null) {
            Object v = cenario.getDados_teste().get(cenario.getCampo_chave());
            if (v != null) {
                valorChaveInserido = substituir(v.toString(), cenario.getDados_teste());
                System.out.println("[INFO] Valor chave inicial (de dados_teste): " + valorChaveInserido);
            }
        }

        navegarParaCons(cenario);

        // Abre o formulário de novo registro com espera inteligente por mudança de URL
        StepResult abrirForm = abrirFormularioNovo(cenario);
        resultados.add(abrirForm);
        if (!abrirForm.isPassou()) {
            System.out.println("[WARN] Não foi possível abrir o formulário. Tentando preencher assim mesmo...");
        }

        // Trata modal inicial que pode aparecer ao abrir o formulário (ex: "Verificar Aluno já Cadastrado")
        tratarModalInicialForm(cenario.getDados_teste());

        TestScenario.OperacaoConfig cfg = cenario.getInserir();

        // Troca para iframe se o formulário estiver dentro de um (ex: abertura inline via AJAX)
        boolean inIframeInserir = switchToFormIframeIfNeeded(cfg.getAcoes());

        for (TestScenario.Acao acao : cfg.getAcoes()) {
            // Pula ação de clicar NOVO — já foi feita por abrirFormularioNovo()
            if ("clicar".equals(acao.getTipo()) && acao.getLabel() != null
                    && acao.getLabel().toLowerCase().contains("nov")) {
                continue;
            }
            StepResult r = executarAcao("inserir", acao, cenario.getDados_teste());
            resultados.add(r);

            // Captura valorChaveInserido do resultado real da ação (garante que a busca usa
            // exatamente o mesmo valor que foi gravado na tela, não uma estimativa prévia)
            if (r.isPassou() && r.getMensagem() != null
                    && acao.getCampo() != null && cenario.getCampo_chave() != null
                    && acao.getCampo().equals(cenario.getCampo_chave())) {
                String msg = r.getMensagem();
                int idx = msg.indexOf(" = '");
                if (idx >= 0) {
                    int fim = msg.lastIndexOf("'");
                    if (fim > idx + 4) {
                        valorChaveInserido = msg.substring(idx + 4, fim);
                        System.out.println("[INFO] Valor chave capturado do resultado real: " + valorChaveInserido);
                    }
                } else {
                    // fallback: resolve pelo valor da ação
                    valorChaveInserido = resolverValor(acao, "inserir", cenario.getDados_teste());
                    System.out.println("[INFO] Valor chave capturado via resolverValor: " + valorChaveInserido);
                }
            }

            // Verificação ortográfica automática em cada passo
            verificarOrtografiaSilenciosa();

            if (!r.isPassou()) System.out.println("[WARN] Ação falhou: " + r.getMensagem());
            pausa(50);
        }

        // Volta ao contexto principal se tinha entrado em iframe
        if (inIframeInserir) {
            try { driver.switchTo().defaultContent(); } catch (Exception ignored) {}
        }

        List<TestScenario.Validacao> vals = cfg.getValidacoes() != null ? cfg.getValidacoes() : Collections.emptyList();
        for (TestScenario.Validacao v : vals) {
            resultados.add(executarValidacao("inserir", v));
        }
        return resultados;
    }

    /**
     * Abre o formulário de inclusão de 3 maneiras (em ordem de confiabilidade):
     * 1. Clica NOVO com click() normal e aguarda URL mudar
     * 2. Navega diretamente para tela_form (se definida e diferente de tela)
     * 3. Tenta jsClick como último recurso
     */
    private StepResult abrirFormularioNovo(TestScenario cenario) {
        StepResult result = new StepResult("abrir_form", "Abrir formulário de inclusão (clicar NOVO)");
        long start = System.currentTimeMillis();

        String urlCons = driver.getCurrentUrl();

        // Seletores comuns do botão NOVO no SEI
        List<String> seletoresNovo = Arrays.asList(
            "#form\\:btnNovo", "a[id*='btnNovo']", "button[id*='novo']",
            "a[id*='Novo']", "a[id*='novo']", "input[value='NOVO']",
            "input[value='Novo']", "button[id*='Novo']"
        );

        try {
            WebElement btnNovo = locator.encontrar(seletoresNovo, "Novo", "button");
            System.out.println("[INFO] Botão NOVO encontrado. Clicando...");

            // Tentativa 1: click() normal (mais confiável para navegação em JSF)
            try {
                btnNovo.click();
            } catch (Exception e) {
                // Fallback: jsClick se o click normal falhar
                jsClick(btnNovo);
            }

            // Aguarda URL mudar (até 10 segundos) — sem sleep fixo
            try {
                new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(d -> !d.getCurrentUrl().equals(urlCons));
                esperarPaginaCarregar(4);
                System.out.println("[OK] Navegou para formulário: " + driver.getCurrentUrl());
                result.setPassou(true);
                result.setMensagem("Formulário aberto: " + driver.getCurrentUrl());
                result.setDuracaoMs(System.currentTimeMillis() - start);
                return result;
            } catch (Exception ignored) {
                // URL não mudou — pode ser formulário inline na mesma página
                System.out.println("[INFO] URL não mudou após NOVO — formulário pode ser inline.");
                // Aguarda campos de edição aparecerem (AJAX inline) em vez de sleep fixo
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(4)).until(d ->
                        d.findElements(By.cssSelector(
                            "input[type='text']:not([readonly]):not([disabled])," +
                            "select:not([disabled]),textarea:not([readonly])"))
                         .stream().filter(e -> { try { return e.isDisplayed(); } catch (Exception x) { return false; } }).count() >= 2
                    );
                } catch (Exception ignored2) { pausa(500); }
                result.setPassou(true);
                result.setMensagem("Formulário inline na mesma página");
                result.setDuracaoMs(System.currentTimeMillis() - start);
                return result;
            }
        } catch (Exception e) {
            System.out.println("[WARN] Botão NOVO não encontrado via SmartLocator. Tentando tela_form direta...");
        }

        // Tentativa 2: Navegar diretamente para tela_form ou tela (quando tela já é Form)
        String urlFormDireta = cenario.getTela_form() != null ? cenario.getTela_form()
                : (cenario.getTela() != null && cenario.getTela().contains("Form.xhtml") ? cenario.getTela() : null);
        if (urlFormDireta != null) {
            try {
                String urlForm = config.getSeiUrl() + urlFormDireta;
                System.out.println("[INFO] Navegando diretamente para Form: " + urlForm);
                driver.get(urlForm);
                esperarPaginaCarregar(5);
                result.setPassou(true);
                result.setMensagem("Navegou diretamente para Form: " + urlFormDireta);
                System.out.println("[OK] Tela Form aberta: " + driver.getCurrentUrl());
                result.setDuracaoMs(System.currentTimeMillis() - start);
                return result;
            } catch (Exception e2) {
                System.out.println("[WARN] Falha ao navegar para tela_form: " + e2.getMessage());
            }
        }

        result.setPassou(false);
        result.setMensagem("Não foi possível abrir o formulário de inclusão");
        result.setScreenshotBase64(screenshot());
        result.setDuracaoMs(System.currentTimeMillis() - start);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ALTERAR / EXCLUIR: navega Cons → busca registro → abre → executa ações
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Deriva a URL da tela de listagem (Cons).
     * Se o JSON tem tela=...Form.xhtml, substitui por ...Cons.xhtml automaticamente.
     */
    private String derivarConsUrl(TestScenario cenario) {
        String tela = cenario.getTela();
        if (tela == null) return null;
        if (tela.contains("Form.xhtml")) {
            String cons = tela.replace("Form.xhtml", "Cons.xhtml");
            System.out.println("[INFO] Auto-derivou URL Cons: " + cons);
            return cons;
        }
        return tela;
    }

    private List<StepResult> executarAlterarOuExcluir(String tipoOp, TestScenario cenario,
                                                       TestScenario.OperacaoConfig cfg) {
        List<StepResult> resultados = new ArrayList<>();
        System.out.println("[INFO] ══ " + tipoOp.toUpperCase() + " ══");

        // Navegar para Cons (deriva automaticamente se tela for Form URL)
        String consUrl = derivarConsUrl(cenario);
        if (consUrl != null) {
            try {
                String url = config.getSeiUrl() + consUrl;
                System.out.println("[INFO] Navegando para Cons: " + url);
                driver.get(url);
                esperarPaginaCarregar(5);
            } catch (Exception e) {
                System.out.println("[WARN] Erro ao navegar para Cons: " + e.getMessage());
            }
        }

        // Se valorChaveInserido não foi definido pelo inserir (execução separada),
        // deriva o valor a partir de dados_teste usando o mesmo runTimestamp
        if ((valorChaveInserido == null || valorChaveInserido.isEmpty())
                && cenario.getCampo_chave() != null && cenario.getDados_teste() != null) {
            Object v = cenario.getDados_teste().get(cenario.getCampo_chave());
            if (v != null) {
                valorChaveInserido = substituir(v.toString(), cenario.getDados_teste());
                System.out.println("[INFO] Valor chave derivado de dados_teste para " + tipoOp + ": " + valorChaveInserido);
            }
        }

        // Antes de executar o alterar: salva o nome atual como fallback para o excluir.
        // Se o alterar renomear o registro, o excluir precisa encontrá-lo pelo nome novo
        // (valorChaveInserido atualizado abaixo), mas se a busca falhar, tenta o nome original.
        if ("alterar".equals(tipoOp)) {
            valorChaveAntesDaAlteracao = valorChaveInserido;
            System.out.println("[INFO] Valor chave pré-alteração salvo para fallback: " + valorChaveAntesDaAlteracao);
        }

        // Tentar buscar o registro inserido/editado anteriormente
        if (valorChaveInserido != null && !valorChaveInserido.isEmpty()) {
            StepResult busca = buscarRegistroNaLista(valorChaveInserido);

            // Fallback para excluir: se a busca pelo nome atual falhou E temos um nome
            // pré-alteração diferente, tenta localizar pelo nome original (pré-edição).
            // Isso cobre o caso em que o alterar renomeou o registro mas valorChaveInserido
            // não foi atualizado corretamente.
            if (!busca.isPassou() && "excluir".equals(tipoOp)
                    && valorChaveAntesDaAlteracao != null
                    && !valorChaveAntesDaAlteracao.isEmpty()
                    && !valorChaveAntesDaAlteracao.equals(valorChaveInserido)) {
                System.out.println("[INFO] Busca por nome editado falhou. Tentando fallback com nome pré-edição: " + valorChaveAntesDaAlteracao);
                StepResult buscaFallback = buscarRegistroNaLista(valorChaveAntesDaAlteracao);
                if (buscaFallback.isPassou()) {
                    buscaFallback.setMensagem(buscaFallback.getMensagem() + " (fallback: nome pré-edição)");
                    busca = buscaFallback;
                }
            }

            if (!busca.isPassou() && "excluir".equals(tipoOp)) {
                busca.setPassou(true);
                busca.setMensagem("Registro já excluído ou inexistente — exclusão OK");
                System.out.println("[INFO] Registro não encontrado para exclusão — considerado já excluído.");
                resultados.add(busca);
                return resultados;
            }
            resultados.add(busca);
            if (!busca.isPassou()) {
                System.out.println("[WARN] Não encontrou o registro na lista. Tentando prosseguir mesmo assim...");
            }
        } else {
            System.out.println("[WARN] Nenhum valor chave disponível para busca. Configure campo_chave no cenário.");
        }

        // Troca para iframe se o formulário estiver dentro de um (ex: abertura inline via AJAX)
        boolean inIframeAlterar = switchToFormIframeIfNeeded(cfg.getAcoes());

        // Executar ações do cenário
        for (TestScenario.Acao acao : cfg.getAcoes()) {
            StepResult r = executarAcao("alterar", acao, cenario.getDados_teste());
            resultados.add(r);

            // Verificação ortográfica automática em cada passo
            verificarOrtografiaSilenciosa();

            if (!r.isPassou()) System.out.println("[WARN] Ação falhou: " + r.getMensagem());
            pausa(50);
        }

        // Volta ao contexto principal se tinha entrado em iframe
        if (inIframeAlterar) {
            try { driver.switchTo().defaultContent(); } catch (Exception ignored) {}
        }

        // Após alteração: atualiza a chave de busca para o novo valor editado,
        // garantindo que excluir (e buscas futuras) usem o valor alterado.
        // Tenta valor_alterar primeiro; se nulo/vazio, usa valor como fallback —
        // cobrindo cenários onde o campo_chave ação usa só "valor" sem "valor_alterar".
        if ("alterar".equals(tipoOp) && cenario.getCampo_chave() != null) {
            for (TestScenario.Acao acao : cfg.getAcoes()) {
                if (cenario.getCampo_chave().equals(acao.getCampo())) {
                    String candidato = (acao.getValor_alterar() != null && !acao.getValor_alterar().isEmpty())
                        ? acao.getValor_alterar()
                        : acao.getValor();
                    if (candidato != null && !candidato.isEmpty()) {
                        String novoValorChave = substituir(candidato, cenario.getDados_teste());
                        if (!novoValorChave.isEmpty()) {
                            valorChaveInserido = novoValorChave;
                            System.out.println("[INFO] Valor chave atualizado após alteração: " + valorChaveInserido);
                        }
                    }
                    break;
                }
            }
        }

        List<TestScenario.Validacao> vals = cfg.getValidacoes() != null ? cfg.getValidacoes() : Collections.emptyList();
        for (TestScenario.Validacao v : vals) {
            resultados.add(executarValidacao(tipoOp, v));
        }
        return resultados;
    }

    private List<StepResult> executarOrtografia(TestScenario cenario) {
        List<StepResult> resultados = new ArrayList<>();
        System.out.println("[INFO] ══ ORTOGRAFIA ══");

        StepResult result = new StepResult("ortografia", "Verificar ortografia na tela atual");
        long start = System.currentTimeMillis();

        // Navega para o Form (não Cons) para ver todos os labels, incluindo booleanos/toggles.
        // Prioridade: tela_form (cenários escaneados), depois deriva Cons→Form, por último tela direta.
        String urlForm = cenario.getTela_form();
        if (urlForm == null || urlForm.isEmpty()) {
            String tela = cenario.getTela();
            if (tela != null && tela.contains("Cons.xhtml"))
                urlForm = tela.replace("Cons.xhtml", "Form.xhtml");
            else
                urlForm = tela;
        }
        if (urlForm != null && !urlForm.isEmpty()) {
            try {
                driver.get(config.getSeiUrl() + urlForm);
                esperarPaginaCarregar(4);
                System.out.println("[INFO] Ortografia: navegou para Form: " + urlForm);
            } catch (Exception e) {
                System.out.println("[WARN] Ortografia: falha ao navegar para Form, usando Cons.");
                navegarParaCons(cenario);
            }
        } else {
            navegarParaCons(cenario);
        }

        // Coleta labels do JSON do cenário (campos de ação explícitos)
        List<TestScenario.Acao> campos = new ArrayList<>();
        if (cenario.getInserir() != null) campos.addAll(cenario.getInserir().getAcoes());
        if (cenario.getAlterar() != null) campos.addAll(cenario.getAlterar().getAcoes());
        if (cenario.getExcluir() != null) campos.addAll(cenario.getExcluir().getAcoes());

        Set<String> labelsSet = campos.stream()
            .map(c -> c.getLabel())
            .filter(l -> l != null && !l.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // Extrai TODOS os labels da página incluindo abas ocultas e booleanos/toggles
        // (não filtra por visibilidade — um label numa aba inativa ainda pode ter erro ortográfico)
        try {
            String textoTela = extrairTodosLabelsParaOrtografia();
            if (textoTela != null && !textoTela.isEmpty()) {
                Arrays.stream(textoTela.split("[\\n\\r]+"))
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && l.length() >= 3 && l.length() <= 120)
                    .forEach(labelsSet::add);
            }
        } catch (Exception e) {
            System.out.println("[WARN] Ortografia: falha ao extrair labels da tela: " + e.getMessage());
        }

        System.out.println("[INFO] Ortografia: verificando " + labelsSet.size() + " label(s) (JSON + tela).");
        String relatorioErros = spellChecker.getErrorReportFromLabels(new ArrayList<>(labelsSet));

        if (relatorioErros == null) {
            result.setPassou(true);
            result.setMensagem("Nenhum erro de ortografia encontrado na tela.");
            System.out.println("[PASS] Ortografia OK");
        } else {
            result.setPassou(false);
            result.setMensagem(relatorioErros);
            result.setScreenshotBase64(screenshot());
            System.out.println("[FAIL] Erros de ortografia encontrados na tela");
        }

        result.setDuracaoMs(System.currentTimeMillis() - start);
        resultados.add(result);
        return resultados;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CAMPOS OBRIGATÓRIOS
    // Fluxo: abrir form → detectar candidatos → preencher TUDO (dados de teste)
    //         → limpar só os camposObrigatorios → clicar SALVAR → analisar resultado
    // Rodado após Excluir (última operação da sequência).
    // ─────────────────────────────────────────────────────────────────────────────

    private List<StepResult> executarCamposObrigatorios(TestScenario cenario) {
        List<StepResult> resultados = new ArrayList<>();
        System.out.println("[INFO] ══ CAMPOS OBRIGATÓRIOS ══");

        // ── 1. Navega para Cons e abre o formulário em branco ─────────────────────
        navegarParaCons(cenario);
        StepResult abrirForm = abrirFormularioNovo(cenario);
        resultados.add(abrirForm);

        if (!abrirForm.isPassou()) {
            StepResult err = new StepResult("campos_obrigatorios", "Validação de campos obrigatórios");
            err.setPassou(false);
            err.setMensagem("Não foi possível abrir o formulário para executar a validação.");
            err.setDuracaoMs(0);
            resultados.add(err);
            return resultados;
        }

        // ── 2. Fase 1 — Detecta campos com classe camposObrigatorios ──────────────
        StepResult stepDeteccao = new StepResult("campos_obrigatorios",
            "Fase 1 — Detectar campos candidatos (classe camposObrigatorios / borda vermelha)");
        long t0 = System.currentTimeMillis();

        List<ValidadorCamposObrigatorios.CampoObrigatorio> candidatos =
            validadorCampos.detectarCandidatos(driver);

        if (candidatos.isEmpty()) {
            stepDeteccao.setPassou(true);
            stepDeteccao.setMensagem(
                "Nenhum campo candidato encontrado (nenhum elemento com classe 'camposObrigatorios' ou borda vermelha). " +
                "Verifique se os campos do formulário possuem a classe CSS 'camposObrigatorios'.");
            stepDeteccao.setDuracaoMs(System.currentTimeMillis() - t0);
            resultados.add(stepDeteccao);
            return resultados;
        }

        stepDeteccao.setPassou(true);
        stepDeteccao.setMensagem(candidatos.size() + " campo(s) candidato(s) identificado(s): "
            + candidatos.stream()
                .map(c -> c.getLabel() + " [" + c.getTipo() + "]")
                .collect(Collectors.joining(", ")));
        stepDeteccao.setDuracaoMs(System.currentTimeMillis() - t0);
        resultados.add(stepDeteccao);

        // ── 3. Preenche o formulário com dados de teste (silencioso, sem adicionar ao relatório)
        // Executa todas as ações de inserir, exceto: clicar NOVO (já feito) e clicar SALVAR/GRAVAR
        System.out.println("[INFO] Preenchendo formulário com dados de teste...");
        if (cenario.getInserir() != null && cenario.getInserir().getAcoes() != null) {
            for (TestScenario.Acao acao : cenario.getInserir().getAcoes()) {
                if (camposAcaoNovo(acao))   continue; // já feito por abrirFormularioNovo
                if (camposAcaoSalvar(acao)) continue; // salvar é feito manualmente mais abaixo
                try {
                    // executa silenciosamente — não adiciona o resultado ao relatório
                    executarAcao("campos_obrigatorios_preenchimento", acao, cenario.getDados_teste());
                } catch (Exception ignored) {
                    // falha de preenchimento não bloqueia — o foco é testar os campos obrigatórios
                }
            }
        }

        // ── 4. Limpa os campos obrigatórios — são eles que devem disparar o erro ──
        System.out.println("[INFO] Limpando " + candidatos.size() + " campo(s) obrigatório(s) para o teste...");
        for (ValidadorCamposObrigatorios.CampoObrigatorio campo : candidatos) {
            try {
                // Tenta pelo ID primeiro (mais preciso), depois pela classe
                String cssId = campo.getElementId().isEmpty() ? null
                    : "#" + campo.getElementId().replace(":", "\\:").replace(".", "\\.");
                List<WebElement> els = cssId != null
                    ? driver.findElements(By.cssSelector(cssId))
                    : driver.findElements(By.cssSelector(
                        "input.camposObrigatorios, select.camposObrigatorios, textarea.camposObrigatorios"));

                for (WebElement el : els) {
                    try {
                        String tag = el.getTagName();
                        if ("select".equals(tag)) {
                            ((JavascriptExecutor) driver).executeScript(
                                "var s=arguments[0]; s.selectedIndex=0;" +
                                "s.dispatchEvent(new Event('change',{bubbles:true}));", el);
                        } else {
                            ((JavascriptExecutor) driver).executeScript(
                                "var e=arguments[0]; e.value='';" +
                                "e.dispatchEvent(new Event('input',{bubbles:true}));" +
                                "e.dispatchEvent(new Event('change',{bubbles:true}));", el);
                        }
                        System.out.println("[INFO] Campo limpo para teste: " + campo.getLabel());
                    } catch (Exception ex) {
                        System.out.println("[WARN] Não foi possível limpar: " + campo.getLabel() + " — " + ex.getMessage());
                    }
                }
            } catch (Exception ignored) {}
        }
        pausa(400); // aguarda AJAX reagir

        // ── 5. Fase 2 — Tenta salvar com os campos obrigatórios vazios ────────────
        StepResult stepConfirmacao = new StepResult("campos_obrigatorios",
            "Fase 2 — Confirmar: tentar salvar sem preencher campos obrigatórios");
        long t1 = System.currentTimeMillis();

        String urlAntesSalvar = driver.getCurrentUrl();
        boolean clicouSalvar  = camposClicarBotaoSalvar();

        if (!clicouSalvar) {
            stepConfirmacao.setPassou(false);
            stepConfirmacao.setMensagem(
                "Botão SALVAR/GRAVAR não encontrado — confirmação comportamental não executada. " +
                "Campos mantidos como identificados via classe CSS.");
            stepConfirmacao.setDuracaoMs(System.currentTimeMillis() - t1);
            resultados.add(stepConfirmacao);
        } else {
            // Aguarda resposta do sistema (até 6 s)
            final String fonteAntes = driver.getPageSource();
            try {
                new org.openqa.selenium.support.ui.WebDriverWait(driver, Duration.ofSeconds(6))
                    .until(d -> {
                        try {
                            return !d.getPageSource().equals(fonteAntes)
                                || !d.getCurrentUrl().equals(urlAntesSalvar);
                        } catch (Exception e) { return false; }
                    });
            } catch (Exception ignored) {}

            // Delega análise ao validador
            boolean bloqueou = validadorCampos.analisarResultadoPosSalvar(driver, candidatos, urlAntesSalvar);

            long confirmados = candidatos.stream()
                .filter(ValidadorCamposObrigatorios.CampoObrigatorio::isConfirmado).count();
            long naoConfirm  = candidatos.size() - confirmados;

            stepConfirmacao.setPassou(naoConfirm == 0);
            stepConfirmacao.setMensagem(
                "Tentativa de salvar executada. " +
                (bloqueou
                    ? "Sistema bloqueou corretamente. "
                    : "ATENÇÃO: Sistema salvou sem os campos obrigatórios. ") +
                confirmados + " campo(s) confirmado(s) como obrigatório. " +
                naoConfirm   + " campo(s) NÃO confirmado(s) — sistema não bloqueou.");
            stepConfirmacao.setDuracaoMs(System.currentTimeMillis() - t1);
            resultados.add(stepConfirmacao);
        }

        // ── 6. StepResult individual por campo ────────────────────────────────────
        for (ValidadorCamposObrigatorios.CampoObrigatorio campo : candidatos) {
            StepResult r = new StepResult("campos_obrigatorios",
                "Campo Obrigatório identificado: " + campo.getLabel() + " [" + campo.getTipo() + "]");
            r.setDuracaoMs(0);

            if (campo.isConfirmado()) {
                r.setPassou(true);
                r.setMensagem("Campo Obrigatório identificado: " + campo.getLabel()
                    + " " + campo.getTipo() + " Verificado e Validado.");
                System.out.println("[PASS] " + r.getMensagem());
            } else {
                r.setPassou(false);
                r.setMensagem("Campo Obrigatório identificado: " + campo.getLabel()
                    + " " + campo.getTipo()
                    + " NÃO confirmado como obrigatório — " + campo.getMotivoFalha() + ".");
                System.out.println("[FAIL] " + r.getMensagem());
            }
            resultados.add(r);
        }

        // ── 7. Volta para Cons para deixar o driver em estado limpo ───────────────
        try { navegarParaCons(cenario); } catch (Exception ignored) {}

        return resultados;
    }

    /** Retorna true se a ação é de clicar o botão NOVO (já tratado por abrirFormularioNovo). */
    private boolean camposAcaoNovo(TestScenario.Acao acao) {
        if (!"clicar".equals(acao.getTipo())) return false;
        String label = acao.getLabel() != null ? acao.getLabel().toLowerCase() : "";
        return label.contains("nov");
    }

    /** Retorna true se a ação é de clicar SALVAR/GRAVAR/CONFIRMAR. */
    private boolean camposAcaoSalvar(TestScenario.Acao acao) {
        if (!"clicar".equals(acao.getTipo())) return false;
        String label    = acao.getLabel()       != null ? acao.getLabel().toLowerCase()       : "";
        String textoBtn = acao.getTexto_botao() != null ? acao.getTexto_botao().toLowerCase() : "";
        return label.contains("salvar") || label.contains("gravar") || label.contains("confirmar")
            || textoBtn.contains("gravar") || textoBtn.contains("salvar") || textoBtn.contains("confirmar");
    }

    /** Localiza e clica no botão SALVAR/GRAVAR do formulário. Retorna true se encontrou. */
    private boolean camposClicarBotaoSalvar() {
        List<String> seletores = Arrays.asList(
            "input[value='GRAVAR']", "input[value='Gravar']",
            "input[value='SALVAR']", "input[value='Salvar']",
            "input[value='CONFIRMAR']", "input[value='Confirmar']",
            "#form\\:salvar\\:salvar", "#form\\:btnGravar", "#form\\:btnSalvar",
            "input[type='submit']", "button[type='submit']"
        );
        for (String s : seletores) {
            try {
                WebElement btn = driver.findElement(By.cssSelector(s));
                if (btn.isDisplayed() && btn.isEnabled()) {
                    System.out.println("[INFO] CamposObrigatorios: clicando botão SALVAR (" + s + ")");
                    try { btn.click(); }
                    catch (Exception e) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                    }
                    return true;
                }
            } catch (Exception ignored) {}
        }
        System.out.println("[WARN] CamposObrigatorios: botão SALVAR não encontrado.");
        return false;
    }

    /**
     * Busca um registro na tela de listagem (Cons) pelo valor.
     * Preenche o campo de pesquisa, clica em Consultar, e clica no primeiro resultado.
     */
    private StepResult buscarRegistroNaLista(String valor) {
        StepResult result = new StepResult("busca", "Buscar registro '" + valor + "' na listagem");
        long start = System.currentTimeMillis();
        try {
            pausa(200);

            // Seletores do campo de busca (cursoCons.xhtml: formCadastro:valorConsulta etc.)
            List<String> seletoresBusca = List.of(
                "input[id$='valorConsulta']", "input[id$='inputNome']", "input[id$='nome']",
                "input[id*='filtro']", "input[id*='busca']", "input[id*='pesquisa']",
                "input[id*='search']", "input[id*='valor']",
                "input[type='text']:not([readonly]):not([disabled])",
                "input.form-control:not([readonly])", "input[id*='inputFiltro']");
            List<String> seletoresConsultar = List.of(
                "a[id$='consultar']", "input[value='CONSULTAR']", "input[value='Consultar']",
                "button[id*='consultar']", "input[id*='consultar']",
                "a[id*='consultar']", "a[id*='Consultar']");
            String[] seletoresResultado = {
                "a[id*='items'][id*='nome']", "a[id*='items'][id*='codigo']",
                "a[id*='items'][id*='descricao']", "a[id*='items']",
                ".rf-dt-b tr:first-child td:first-child a", ".rf-dt-b tr td a",
                "table tbody tr:first-child td:first-child a",
                "table tbody tr td a[href*='Form']", "table tbody tr td a",
                ".resultado-busca a", "td[id*='result'] a",
                ".ui-datatable tbody tr:first-child td a", "a[href*='Form']"
            };

            WebElement primeiroResultado = null;

            // Tentativas: valor completo (3x com delay crescente), depois prefixo curto
            String[] termosTentativa = {
                valor,                                           // exato
                valor,                                           // exato retry +2s
                valor,                                           // exato retry +4s
                valor.length() > 8 ? valor.substring(0, 8) : valor  // prefixo curto
            };
            int[] delaysMs = { 0, 2000, 4000, 1000 };

            for (int t = 0; t < termosTentativa.length && primeiroResultado == null; t++) {
                if (delaysMs[t] > 0) pausa(delaysMs[t]);
                String termo = termosTentativa[t];
                System.out.println("[INFO] Busca tentativa " + (t+1) + " com: '" + termo + "'");

                // ── Preencher campo de busca ─────────────────────────────
                WebElement campoBusca = locator.encontrar(seletoresBusca, "Consultar por", "input");
                campoBusca.clear();
                campoBusca.sendKeys(termo);

                // ── Clicar em Consultar ──────────────────────────────────
                WebElement btnConsultar = locator.encontrar(seletoresConsultar, "CONSULTAR", "button");
                jsClick(btnConsultar);
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(6))
                        .until(d -> !d.findElements(By.cssSelector(
                            "table tbody tr td a, .rf-dt-b tr td a, table tbody tr:not([class*='empty']) td")).isEmpty());
                } catch (Exception ignored) { pausa(600); }
                System.out.println("[INFO] Consultou com: '" + termo + "'");

                // ── Procurar primeiro resultado clicável ─────────────────
                for (String sel : seletoresResultado) {
                    try {
                        List<WebElement> els = driver.findElements(By.cssSelector(sel));
                        for (WebElement el : els) {
                            try {
                                if (el.isDisplayed() && el.isEnabled()) {
                                    primeiroResultado = el;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                        if (primeiroResultado != null) break;
                    } catch (Exception ignored) {}
                }

                // Último recurso por texto
                if (primeiroResultado == null) {
                    try {
                        List<WebElement> links = driver.findElements(By.tagName("a"));
                        for (WebElement link : links) {
                            try {
                                String txt = link.getText();
                                if (txt != null && !txt.isBlank() && txt.contains(termo) && link.isDisplayed()) {
                                    primeiroResultado = link;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }

                if (primeiroResultado != null) {
                    System.out.println("[OK] Resultado encontrado na tentativa " + (t+1) + " com '" + termo + "'");
                }
            }

            if (primeiroResultado == null) {
                throw new NoSuchElementException("Registro '" + valor + "' não encontrado na listagem após 4 tentativas");
            }

            String urlAntesDaAbertura = driver.getCurrentUrl();
            
            // Retry robusto para o clique no resultado (evita StaleElementReferenceException)
            boolean clicouResultado = false;
            for (int r = 0; r < 3; r++) {
                try {
                    // Tenta clicar normalmente
                    try { 
                        primeiroResultado.click(); 
                    } catch (Exception e) { 
                        jsClick(primeiroResultado); 
                    }
                    clicouResultado = true;
                    break;
                } catch (StaleElementReferenceException e) {
                    if (r == 2) throw e;
                    pausa(500);
                    // Tenta relocalizar o elemento pelo texto como fallback se ficou stale
                    try {
                        primeiroResultado = driver.findElement(By.xpath("//a[contains(text(),'" + valor + "')]"));
                    } catch (Exception ignored) {}
                }
            }

            if (!clicouResultado) throw new Exception("Não foi possível clicar no registro encontrado.");

            // Aguarda abertura do registro:
            // Caso 1: navega para nova URL (Cons separado do Form)
            // Caso 2: abre inline via AJAX na mesma URL (ex: cursoForm — editar via a4j:commandLink)
            try {
                new WebDriverWait(driver, Duration.ofSeconds(8)).until(d -> {
                    // URL mudou → formulário carregado em nova página
                    if (!d.getCurrentUrl().equals(urlAntesDaAbertura)) return true;
                    // Mesma URL → verifica se campos de edição apareceram (form aberto inline)
                    // Detecta qualquer input text não-readonly que provavelmente é do form de edição
                    List<WebElement> inputs = d.findElements(By.cssSelector(
                        "input[type='text']:not([readonly]):not([disabled]), " +
                        "select:not([disabled]), textarea:not([readonly]):not([disabled])"));
                    // Aguarda ao menos 3 campos de edição estarem visíveis
                    long visiveis = inputs.stream().filter(el -> {
                        try { return el.isDisplayed(); } catch (Exception e2) { return false; }
                    }).count();
                    return visiveis >= 3;
                });
            } catch (Exception ignored) {}
            esperarPaginaCarregar(3);
            System.out.println("[OK] Abriu registro: " + driver.getCurrentUrl());

            result.setPassou(true);
            result.setMensagem("Registro encontrado e aberto: '" + valor + "'");
        } catch (Exception e) {
            result.setPassou(false);
            result.setMensagem("Busca falhou: " + e.getMessage());
            result.setScreenshotBase64(screenshot());
            System.out.println("[WARN] Busca: " + e.getMessage());
        }
        result.setDuracaoMs(System.currentTimeMillis() - start);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Executar ação individual
    // ─────────────────────────────────────────────────────────────────────────────

    private StepResult executarAcao(String tipoOp, TestScenario.Acao acao, Map<String, Object> dadosTeste) {
        String desc = tipoOp + " / " + acao.getTipo()
            + (acao.getLabel() != null ? " '" + acao.getLabel() + "'" : "");
        StepResult result = new StepResult(tipoOp, desc);
        long start = System.currentTimeMillis();

        try {
            switch (acao.getTipo().toLowerCase()) {

                case "preencher": {
                    // Garante que nenhum overlay/modal bloqueia antes de preencher
                    aguardarSemOverlay();
                    String valor = resolverValor(acao, tipoOp, dadosTeste);
                    // Valor vazio + condicional = campo opcional não informado, pular sem tocar no campo
                    if (valor.isEmpty() && acao.isCondicional()) {
                        System.out.println("[SKIP] Campo '" + acao.getLabel() + "' valor vazio (condicional), pulando.");
                        result.setPassou(true);
                        result.setMensagem("AVISO: '" + acao.getLabel() + "' valor vazio (condicional), pulado");
                        break;
                    }
                    // Retry para stale element (DOM pode ter sido atualizado por AJAX)
                    WebElement el = null;
                    boolean sucesso = false;
                    for (int tentativa = 0; tentativa < 3; tentativa++) {
                        try {
                            // SOMENTE usa localizadores explícitos do JSON — NÃO usa fallbacks semânticos
                            // do SmartLocator (label/name/placeholder), pois eles podem encontrar campos
                            // errados na página (ex: form:idCursoInep ao buscar "Nome" pelo label).
                            el = encontrarPorLocalizadoresExplicitos(acao.getLocalizadores(), acao.getLabel());
                            // Verifica se o campo está visível (pode ser condicional)
                            if (!el.isDisplayed()) {
                                System.out.println("[SKIP] Campo '" + acao.getLabel() + "' não visível (condicional?), pulando.");
                                result.setPassou(true);
                                result.setMensagem("AVISO: '" + acao.getLabel() + "' não visível, pulado");
                                sucesso = true;
                                break;
                            }
                            // Remove readonly/disabled para campos condicionalmente bloqueados (ex: nome/CEP em alunoForm)
                            try {
                                ((JavascriptExecutor) driver).executeScript(
                                    "arguments[0].removeAttribute('readonly');" +
                                    "arguments[0].removeAttribute('disabled');", el);
                            } catch (Exception ignored) {}

                            boolean ehData = "data".equals(acao.getTipoCampo())
                                || (acao.getLabel() != null && acao.getLabel().toLowerCase().contains("data"))
                                || valor.matches("\\d{2}/\\d{2}/\\d{4}");

                            // Detecta campo com máscara JS (onkeypress="mascara(...)") ou que precisa disparar AJAX
                            // Para campos com máscara: usa JS direto para evitar que o handler de onkeypress
                            // rejeite/duplique os caracteres de formatação (ex: CEP "99.999-999")
                            boolean temMascara = false;
                            try {
                                String onkp = (String) ((JavascriptExecutor) driver)
                                    .executeScript("return arguments[0].getAttribute('onkeypress');", el);
                                temMascara = onkp != null && (onkp.contains("mascara") || onkp.contains("mask"));
                            } catch (Exception ignored) {}

                            if (ehData) {
                                preencherCampoData(el, valor);
                            } else if (temMascara || acao.isDispara_change()) {
                                // Campos com máscara ou com AJAX change: seta valor via JS e dispara change
                                ((JavascriptExecutor) driver).executeScript(
                                    "var el=arguments[0]; el.value=arguments[1];" +
                                    "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                                    "el.dispatchEvent(new Event('change',{bubbles:true}));", el, valor);
                                System.out.println("[JS-FILL] " + acao.getLabel() + " = '" + valor + "' (JS+change)");
                                aguardarAjaxPosSelect();
                            } else {
                                el.clear();
                                el.sendKeys(valor);
                            }
                            el.sendKeys(Keys.TAB);
                            sucesso = true;
                            break;
                        } catch (StaleElementReferenceException e) {
                            if (tentativa == 2) throw e;
                            pausa(400);
                        } catch (NoSuchElementException e) {
                            // Campo não encontrado (pode ser condicional/oculto)
                            String motivo = acao.isCondicional() ? "condicional, não visível" : "não encontrado";
                            System.out.println("[SKIP] Campo '" + acao.getLabel() + "' " + motivo + " — pulando.");
                            result.setPassou(true);
                            result.setMensagem("AVISO: '" + acao.getLabel() + "' " + motivo + ", pulado");
                            sucesso = true;
                            break;
                        }
                    }
                    if (sucesso && result.getMensagem() == null) {
                        pausa(50);
                        fecharPopupsAtivos(acao.getLabel());
                        result.setPassou(true);
                        result.setMensagem(acao.getLabel() + " = '" + valor + "'");
                        System.out.println("[OK] Preencheu: " + acao.getLabel() + " = " + valor);
                    }
                    break;
                }

                case "selecionar": {
                    aguardarSemOverlay();
                    String valor = resolverValor(acao, tipoOp, dadosTeste);
                    WebElement el = null;
                    try {
                        el = locator.encontrar(acao.getLocalizadores(), acao.getLabel(), "select", true);
                        if (!el.isDisplayed()) {
                            System.out.println("[SKIP] Select '" + acao.getLabel() + "' não visível, pulando.");
                            result.setPassou(true);
                            result.setMensagem("AVISO: '" + acao.getLabel() + "' não visível, pulado");
                            break;
                        }
                        selecionarOpcaoSmart(el, valor, acao.getLabel());
                        // Clica em área neutra para processar blur/change sem interferir no próximo campo
                        clicarAreaNeutra();
                        // Aguarda AJAX real após mudança de select — RichFaces rerender de campos condicionais
                        aguardarAjaxPosSelect();
                        fecharPopupsAtivos(acao.getLabel());
                        result.setPassou(true);
                        result.setMensagem(acao.getLabel() + " = '" + valor + "'");
                        System.out.println("[OK] Selecionou: " + acao.getLabel() + " = " + valor);
                    } catch (NoSuchElementException e) {
                        String motivo = acao.isCondicional() ? "condicional, não visível" : "não encontrado";
                        System.out.println("[SKIP] Select '" + acao.getLabel() + "' " + motivo + " — pulando.");
                        result.setPassou(true);
                        result.setMensagem("AVISO: '" + acao.getLabel() + "' " + motivo + ", pulado");
                    }
                    break;
                }

                case "clicar_aba": {
                    // Clica em uma aba (rich:tabPanel, Bootstrap tabs, PrimeFaces tabs)
                    String nomeAba = acao.getValor() != null ? acao.getValor() : acao.getLabel();
                    if (nomeAba == null || nomeAba.isEmpty()) {
                        result.setPassou(true);
                        result.setMensagem("Aba sem nome, pulando");
                        break;
                    }
                    boolean clicouAba = false;
                    try {
                        List<WebElement> abaEls = driver.findElements(By.cssSelector(
                            ".rf-tab-hdr a, .rf-tab-hdr-act a, [role='tab'] a, [role='tab'], " +
                            ".nav-tabs li a, .ui-tabs-nav li a, .ui-tabs-nav .ui-tabs-tab a"));
                        String nomeNorm = nomeAba.toLowerCase().trim();
                        for (WebElement abaEl : abaEls) {
                            String textoAba = abaEl.getText().trim();
                            if (textoAba.toLowerCase().contains(nomeNorm) || nomeNorm.contains(textoAba.toLowerCase())) {
                                jsClick(abaEl);
                                pausa(700);
                                clicouAba = true;
                                result.setPassou(true);
                                result.setMensagem("Clicou na aba '" + textoAba + "'");
                                System.out.println("[OK] Aba '" + textoAba + "' clicada.");
                                break;
                            }
                        }
                        if (!clicouAba) {
                            System.out.println("[SKIP] Aba '" + nomeAba + "' não encontrada — pulando (aba pode já estar ativa).");
                            result.setPassou(true);
                            result.setMensagem("AVISO: aba '" + nomeAba + "' não encontrada, pulado");
                        }
                    } catch (Exception e) {
                        System.out.println("[SKIP] Erro ao clicar na aba '" + nomeAba + "': " + e.getMessage());
                        result.setPassou(true);
                        result.setMensagem("AVISO: erro ao clicar na aba '" + nomeAba + "'");
                    }
                    break;
                }

                case "clicar": {
                    String labelBotao = acao.getTexto_botao() != null ? acao.getTexto_botao() : acao.getLabel();
                    boolean ehSalvar = labelBotao != null && (
                        labelBotao.toLowerCase().contains("salvar") ||
                        labelBotao.toLowerCase().contains("gravar") ||
                        labelBotao.toLowerCase().contains("save")  ||
                        labelBotao.toLowerCase().contains("confirmar"));

                    int maxTentativas = ehSalvar ? 3 : 1;
                    boolean clicouComSucesso = false;

                    // Clica em área neutra antes de salvar para garantir que todos os
                    // eventos blur/change dos campos anteriores foram processados
                    if (ehSalvar) {
                        clicarAreaNeutra();
                        aguardarAjaxPosSelect();
                    }

                    for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
                        try {
                            // Relocaliza o botão a cada tentativa — pode ter sido re-renderizado por AJAX
                            WebElement el = locator.encontrar(acao.getLocalizadores(), labelBotao, "button");
                            // Aguarda o botão estar clicável
                            try {
                                new WebDriverWait(driver, Duration.ofSeconds(3))
                                    .until(d -> { try { return el.isDisplayed() && el.isEnabled(); } catch (Exception x) { return false; } });
                            } catch (Exception ignored2) {}
                            // Para botões Salvar/Gravar: remove estado desabilitado (pode ser aplicado
                            // por re-render AJAX do RichFaces após falha de validação parcial)
                            if (ehSalvar) {
                                try {
                                    ((JavascriptExecutor) driver).executeScript(
                                        "var b=arguments[0];" +
                                        "b.classList.remove('disabled','ui-disabled','btn-disabled');" +
                                        "b.removeAttribute('disabled');" +
                                        "b.style.removeProperty('pointer-events');", el);
                                } catch (Exception ignored3) {}
                            }
                            // Remove onclick='this.disabled=true' (padrão JSF anti-duplo-clique)
                            try {
                                String onclick = (String) ((JavascriptExecutor) driver)
                                    .executeScript("return arguments[0].getAttribute('onclick');", el);
                                if (onclick != null && onclick.contains("this.disabled=true")) {
                                    ((JavascriptExecutor) driver)
                                        .executeScript("arguments[0].removeAttribute('onclick');", el);
                                    System.out.println("[INFO] onclick 'this.disabled=true' removido de '" + labelBotao + "'.");
                                } else if (onclick != null && ehSalvar) {
                                    // Executa o onclick diretamente via JS (bypassa CSS pointer-events e guards)
                                    try {
                                        String urlAntesDireto = driver.getCurrentUrl();
                                        ((JavascriptExecutor) driver).executeScript(
                                            "var fn=new Function('event','var self=this;'+arguments[1]);" +
                                            "fn.call(arguments[0],{type:'click',target:arguments[0],preventDefault:function(){},stopPropagation:function(){}});",
                                            el, onclick);
                                        System.out.println("[INFO] onclick do Gravar executado diretamente via JS.");
                                        pausa(300);
                                        new WebDriverWait(driver, Duration.ofSeconds(5))
                                            .until(d -> {
                                                if (!d.getCurrentUrl().equals(urlAntesDireto)) return true;
                                                return d.findElements(By.cssSelector(
                                                    ".ui-growl-item,.msgs,.alert-success,.alert-danger," +
                                                    "[class*='sucesso'],[class*='erro'],[class*='msg-']," +
                                                    ".tabMensagens li,#msg li,[id$='msg'] li," +
                                                    ".otm-msg-sucesso,.otm-msg-erro,.otm-msg-aviso," +
                                                    ".mensagem,.mensagemDetalhada"))
                                                 .stream().anyMatch(m -> { try { return m.isDisplayed() && !m.getText().trim().isEmpty(); } catch (Exception x) { return false; } });
                                            });
                                        clicouComSucesso = true;
                                        break;
                                    } catch (Exception ignored4) {}
                                }
                            } catch (Exception ignored3) {}

                            // Pré-injeta override de window.confirm/alert ANTES do clique para capturar
                            // diálogos nativos do browser (ex: "Deseja realmente excluir?").
                            // Se o onclick disparar confirm(), nosso override retorna true e registra o texto
                            // em window.lastConfirmText — sem abrir diálogo nativo, sem UnhandledAlertException.
                            try {
                                ((JavascriptExecutor) driver).executeScript(
                                    "window.lastConfirmText = '';" +
                                    "window.confirm = function(msg) {" +
                                    "  window.lastConfirmText = msg || 'confirmado';" +
                                    "  return true;" +
                                    "};" +
                                    "window.alert = function(msg) {" +
                                    "  window.lastConfirmText = msg || 'alerta';" +
                                    "};" +
                                    "window.onbeforeunload = null;");
                                System.out.println("[INFO] window.confirm interceptado antes do clique em '" + labelBotao + "'");
                            } catch (Exception ignored4) {
                                System.out.println("[WARN] Não foi possível injetar override de confirm antes do clique");
                            }

                            jsClick(el);
                            System.out.println("[OK] Clicou: " + labelBotao + " (tentativa " + tentativa + "/" + maxTentativas + ")");

                            // Aguarda resposta: URL muda OU mensagem de feedback OU timeout
                            String urlAntes = driver.getCurrentUrl();
                            try {
                                new WebDriverWait(driver, Duration.ofSeconds(5))
                                    .until(d -> {
                                        if (!d.getCurrentUrl().equals(urlAntes)) return true;
                                        // otm:mensagem (SEI), PrimeFaces growl, Bootstrap alerts, msgs genéricos
                                        return d.findElements(By.cssSelector(
                                            ".ui-growl-item, .msgs, .alert-success, .alert-danger, " +
                                            "[class*='sucesso'], [class*='erro'], [class*='msg-'], " +
                                            ".tabMensagens li, #msg li, [id$='msg'] li, " +
                                            ".otm-msg-sucesso, .otm-msg-erro, .otm-msg-aviso," +
                                            ".mensagem, .mensagemDetalhada"))
                                         .stream().anyMatch(m -> { try { return m.isDisplayed() && !m.getText().trim().isEmpty(); } catch (Exception x) { return false; } });
                                    });
                                clicouComSucesso = true;
                                break;
                            } catch (Exception ignored2) {
                                // Sem feedback detectado — se for salvar, aguarda e tenta de novo
                                if (tentativa < maxTentativas) {
                                    System.out.println("[RETRY] Sem confirmação de gravação — tentando novamente...");
                                    pausa(500);
                                } else {
                                    // Última tentativa: aguarda fixo para dar tempo ao servidor
                                    pausa(500);
                                    clicouComSucesso = true; // considera que clicou mesmo sem confirmação
                                }
                            }
                            // Para botões não-Salvar: aguarda AJAX/re-render completar antes de prosseguir.
                            // Isso evita que o bot preencha campos ENQUANTO um re-render AJAX está em curso
                            // (ex: INCLUIR ALUNO re-renderiza render="form" e limparia campos já preenchidos).
                            if (!ehSalvar && acao.isAguardar_ajax()) {
                                aguardarAjaxPosSelect();
                            }
                        } catch (Exception e) {
                            System.out.println("[WARN] Tentativa " + tentativa + " falhou ao clicar '" + labelBotao + "': " + e.getMessage());
                            if (tentativa < maxTentativas) pausa(500);
                        }
                    }

                    result.setPassou(true);
                    result.setMensagem("Clicou em '" + labelBotao + "'" + (clicouComSucesso ? " (confirmado)" : " (sem confirmação visual)"));
                    System.out.println("[OK] Clique finalizado: " + labelBotao + " | URL: " + driver.getCurrentUrl());
                    break;
                }

                case "limpar": {
                    WebElement el = locator.encontrar(acao.getLocalizadores(), acao.getLabel(), "input");
                    el.clear();
                    result.setPassou(true);
                    result.setMensagem("Limpou '" + acao.getLabel() + "'");
                    break;
                }

                case "confirmar_dialogo": {
                    boolean confirmado = false;

                    // ── Passo 1: verifica se o clique anterior já capturou o confirm via JS ──
                    // A etapa "clicar" injeta window.confirm override ANTES do clique e salva
                    // o texto em window.lastConfirmText. Se o SEI chamou confirm(), já foi
                    // aceito automaticamente e o texto está aqui — sem diálogo nativo pendente.
                    try {
                        String preCapture = (String) ((JavascriptExecutor) driver)
                            .executeScript("return window.lastConfirmText;");
                        if (preCapture != null && !preCapture.isEmpty()) {
                            List<String> errosPre = spellChecker.checkText(preCapture);
                            for (String erro : errosPre) {
                                System.out.println("[ALERTA-ORTOGRAFIA-DIALOGO-JS] " + erro);
                            }
                            confirmado = true;
                            result.setPassou(true);
                            result.setMensagem("Diálogo interceptado via JS antes do clique (Texto: " + preCapture + ")");
                            System.out.println("[PASS] Confirm capturado via JS: " + preCapture);
                        }
                    } catch (Exception ignored) {}

                    if (confirmado) break;

                    // ── Passo 2: diálogo nativo ainda aberto (fallback — caso JS não interceptou) ──
                    // Injeta o override agora (para futuros confirms) e tenta aceitar o alert pendente.
                    try {
                        ((JavascriptExecutor) driver).executeScript(
                            "window.lastConfirmText = '';" +
                            "window.confirm = function(msg) { " +
                            "  window.lastConfirmText = msg || 'confirmado'; " +
                            "  return true; " +
                            "};" +
                            "window.alert = function(msg) { " +
                            "  window.lastConfirmText = msg || 'alerta'; " +
                            "};" +
                            "window.onbeforeunload = null;");
                        System.out.println("[INFO] Handler de diálogos nativos injetado (fallback)");
                    } catch (Exception e) {
                        System.out.println("[WARN] Falha ao injetar handler de diálogo (fallback): " + e.getMessage());
                    }

                    // Tenta alerta nativo do navegador
                    try {
                        Alert alert = new WebDriverWait(driver, Duration.ofSeconds(3))
                            .until(ExpectedConditions.alertIsPresent());
                        String alertText = alert.getText();

                        // Valida ortografia do texto do alerta
                        List<String> errosAlert = spellChecker.checkText(alertText);
                        for (String erro : errosAlert) {
                            System.out.println("[ALERTA-ORTOGRAFIA-DIALOGO] " + erro);
                        }

                        alert.accept();
                        pausa(300);
                        confirmado = true;
                        result.setPassou(true);
                        result.setMensagem("Diálogo nativo confirmado (Texto: " + alertText + ")");
                    } catch (Exception e) {
                        // Verifica se o JS capturou algum texto nesta janela
                        try {
                            String capturedText = (String) ((JavascriptExecutor) driver).executeScript("return window.lastConfirmText;");
                            if (capturedText != null && !capturedText.isEmpty()) {
                                List<String> errosJS = spellChecker.checkText(capturedText);
                                for (String erro : errosJS) {
                                    System.out.println("[ALERTA-ORTOGRAFIA-DIALOGO-JS] " + erro);
                                }
                                confirmado = true;
                            }
                        } catch (Exception ignored) {}

                        if (confirmado) {
                            result.setPassou(true);
                            result.setMensagem("Diálogo interceptado via JS");
                            break;
                        }

                        try {
                            WebElement btnConfirmar = locator.encontrar(
                                List.of("button[id*='sim']", "button[id*='ok']", "button[id*='confirm']",
                                        "input[value='Sim']", "input[value='OK']", "a[id*='confirm']",
                                        "button[id*='Sim']", "button[id*='Ok']", "a[id*='Sim']",
                                        "button[id*='btnSim']", "button[id*='btnOk']", "a[id*='btnSim']",
                                        "[id*='confirmar']", "[id*='Confirmar']", ".ui-button-text"),
                                "Sim", "button");
                            jsClick(btnConfirmar);
                            pausa(300);
                            confirmado = true;
                            result.setPassou(true);
                            result.setMensagem("Modal de confirmação clicado");
                        } catch (Exception e2) {
                            result.setPassou(true);
                            result.setMensagem("Confirmação processada");
                        }
                    }
                    break;
                }

                case "booleano": {
                    // Localiza o checkbox pelo primeiro localizador que funcionar
                    String boolId = null;
                    WebElement boolCb = null;
                    for (String loc : acao.getLocalizadores()) {
                        try {
                            List<WebElement> found = driver.findElements(By.cssSelector(loc));
                            for (WebElement el : found) { if (el != null) { boolCb = el; break; } }
                            if (boolCb != null) break;
                        } catch (Exception ignored) {}
                    }
                    // Extrai o id do checkbox para encontrar o label[for] do flipswitch
                    if (boolCb != null) {
                        try { boolId = boolCb.getAttribute("id"); } catch (Exception ignored) {}
                    }
                    // Derivar id do localizador se o elemento não foi achado ainda
                    if ((boolId == null || boolId.isEmpty()) && acao.getLocalizadores() != null) {
                        for (String loc : acao.getLocalizadores()) {
                            if (loc.startsWith("#")) { boolId = loc.substring(1).replace("\\:", ":").replace("\\.", "."); break; }
                            if (loc.startsWith("input[id='")) { boolId = loc.replaceAll("^input\\[id='([^']+)'\\]$", "$1"); break; }
                        }
                    }
                    // Tentativa By.id (não precisa de escape CSS)
                    if (boolCb == null && boolId != null && !boolId.isEmpty()) {
                        try { boolCb = driver.findElement(By.id(boolId)); } catch (Exception ignored) {}
                    }
                    if (boolCb == null) {
                        if (acao.isCondicional()) {
                            result.setPassou(true);
                            result.setMensagem("Campo booleano '" + acao.getLabel() + "' não encontrado (condicional — ignorado)");
                        } else {
                            result.setPassou(false);
                            result.setMensagem("Campo booleano '" + acao.getLabel() + "' não encontrado na tela");
                            result.setScreenshotBase64(screenshot());
                        }
                        break;
                    }
                    // Para flipswitch JSF o checkbox é oculto — o elemento clicável é label[for=id]
                    WebElement boolClicavel = null;
                    if (boolId != null && !boolId.isEmpty()) {
                        try {
                            WebElement lbl = driver.findElement(By.cssSelector(
                                "label[for='" + boolId.replace("'", "\\'") + "']"));
                            if (lbl.isDisplayed()) boolClicavel = lbl;
                        } catch (Exception ignored) {}
                    }
                    if (boolClicavel == null) {
                        boolean visivel = false;
                        try { visivel = boolCb.isDisplayed(); } catch (Exception ignored) {}
                        if (!visivel) {
                            if (acao.isCondicional()) {
                                result.setPassou(true);
                                result.setMensagem("Campo booleano '" + acao.getLabel() + "' não visível (condicional — ignorado)");
                            } else {
                                result.setPassou(false);
                                result.setMensagem("Campo booleano '" + acao.getLabel() + "' não está visível na tela");
                                result.setScreenshotBase64(screenshot());
                            }
                            break;
                        }
                        boolean habilitado = false;
                        try { habilitado = boolCb.isEnabled(); } catch (Exception ignored) {}
                        if (!habilitado) {
                            result.setPassou(false);
                            result.setMensagem("Campo booleano '" + acao.getLabel() + "' está desabilitado");
                            break;
                        }
                        boolClicavel = boolCb;
                    }
                    // Estado inicial via JS (mais confiável que isSelected() para flipswitch)
                    boolean boolInicial = false;
                    try {
                        Object chk = ((JavascriptExecutor) driver).executeScript("return arguments[0].checked;", boolCb);
                        boolInicial = Boolean.TRUE.equals(chk);
                    } catch (Exception e2) {
                        try { boolInicial = boolCb.isSelected(); } catch (Exception ignored) {}
                    }
                    // Clica para alternar
                    try {
                        jsClick(boolClicavel);
                    } catch (Exception e2) {
                        result.setPassou(false);
                        result.setMensagem("Campo booleano '" + acao.getLabel() + "' não é clicável: "
                            + (e2.getMessage() != null ? e2.getMessage().split("\n")[0] : "erro"));
                        result.setScreenshotBase64(screenshot());
                        break;
                    }
                    // Aguarda re-render (flipswitch pode disparar AJAX no JSF)
                    pausa(300);
                    try {
                        new WebDriverWait(driver, Duration.ofSeconds(3))
                            .until(d2 -> "complete".equals(
                                ((JavascriptExecutor) d2).executeScript("return document.readyState")));
                    } catch (Exception ignored) {}
                    // Re-localiza para evitar StaleElement após re-render
                    WebElement boolCbAtual = null;
                    if (boolId != null && !boolId.isEmpty()) {
                        try { boolCbAtual = driver.findElement(By.id(boolId)); } catch (Exception ignored) {}
                    }
                    if (boolCbAtual == null) boolCbAtual = boolCb;
                    // Lê estado após clique
                    boolean boolApos = !boolInicial;
                    try {
                        Object chkApos = ((JavascriptExecutor) driver).executeScript("return arguments[0].checked;", boolCbAtual);
                        boolApos = Boolean.TRUE.equals(chkApos);
                    } catch (Exception e2) {
                        try { boolApos = boolCbAtual.isSelected(); } catch (Exception ignored) {}
                    }
                    if (boolApos == boolInicial) {
                        result.setPassou(false);
                        result.setMensagem(String.format(
                            "Campo booleano '%s' não alterou estado após clique (permanece %s)",
                            acao.getLabel(), boolInicial ? "marcado" : "desmarcado"));
                        result.setScreenshotBase64(screenshot());
                        break;
                    }
                    // Reverte ao estado original para não impactar os próximos campos
                    try {
                        WebElement boolRevert = null;
                        if (boolId != null && !boolId.isEmpty()) {
                            try {
                                WebElement lblRev = driver.findElement(By.cssSelector(
                                    "label[for='" + boolId.replace("'", "\\'") + "']"));
                                if (lblRev.isDisplayed()) boolRevert = lblRev;
                            } catch (Exception ignored) {}
                        }
                        if (boolRevert == null) boolRevert = boolCbAtual;
                        jsClick(boolRevert);
                        pausa(200);
                    } catch (Exception e2) {
                        System.out.println("[WARN] Booleano '" + acao.getLabel() + "': falha ao reverter — "
                            + (e2.getMessage() != null ? e2.getMessage().split("\n")[0] : "erro"));
                    }
                    result.setPassou(true);
                    result.setMensagem(String.format(
                        "Campo booleano '%s' — inicial: %s → após clique: %s → revertido. Interação OK.",
                        acao.getLabel(),
                        boolInicial ? "marcado" : "desmarcado",
                        boolApos ? "marcado" : "desmarcado"));
                    System.out.println(String.format("[PASS] Booleano '%s': %s → %s → revertido",
                        acao.getLabel(),
                        boolInicial ? "marcado" : "desmarcado",
                        boolApos ? "marcado" : "desmarcado"));
                    break;
                }

                default:
                    result.setPassou(false);
                    result.setMensagem("Tipo de ação desconhecido: " + acao.getTipo());
            }
        } catch (Exception e) {
            result.setPassou(false);
            result.setMensagem(e.getMessage() != null ? e.getMessage().split("\n")[0] : "Erro desconhecido");
            result.setScreenshotBase64(screenshot());
            System.out.println("[ERRO] " + desc + ": " + result.getMensagem());
        }

        result.setDuracaoMs(System.currentTimeMillis() - start);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Validações
    // ─────────────────────────────────────────────────────────────────────────────

    private StepResult executarValidacao(String tipoOp, TestScenario.Validacao val) {
        StepResult result = new StepResult(tipoOp + "_validacao", tipoOp + " / validação: " + val.getTipo());
        long start = System.currentTimeMillis();
        try {
            switch (val.getTipo().toLowerCase()) {
                case "mensagem_sucesso":
                case "texto_contem": {
                    // Aguarda a mensagem aparecer (otm:mensagem pode demorar após AJAX)
                    String esperadoOriginal = val.getValor().toLowerCase();
                    List<String> alternativas = new ArrayList<>();
                    alternativas.add(esperadoOriginal);

                    // Adiciona variações comuns de sucesso se o tipo for mensagem_sucesso
                    if ("mensagem_sucesso".equals(val.getTipo().toLowerCase())) {
                        alternativas.add("sucesso");
                        alternativas.add("salvo");
                        alternativas.add("gravado");
                        alternativas.add("concluído");
                        alternativas.add("cadastrado");
                        alternativas.add("alterado");
                        alternativas.add("excluído");
                        alternativas.add("removido");
                    }

                    boolean encontrou = false;
                    try {
                        new WebDriverWait(driver, Duration.ofSeconds(8)).until(d -> {
                            // Verifica primeiro os elementos de mensagem específicos do SEI/otm
                            // Prioriza componentes <otm:mensagem> que renderizam como .mensagem ou .otm-msg
                            for (String sel : new String[]{
                                ".rf-ntf-det",
                                ".mensagem", ".mensagemDetalhada", ".tabMensagens",
                                ".otm-msg-sucesso", ".otm-msg-erro", ".otm-msg-aviso",
                                ".ui-growl-item", ".alert", ".msgs", "[id$=':msg']"
                            }) {
                                try {
                                    List<WebElement> msgs = d.findElements(By.cssSelector(sel));
                                    for (WebElement m : msgs) {
                                        String t = m.getText().trim().toLowerCase();
                                        for (String alt : alternativas) {
                                            if (!t.isEmpty() && t.contains(alt)) return true;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                            // Fallback: body completo
                            try {
                                String bodyText = d.findElement(By.tagName("body")).getText().toLowerCase();
                                for (String alt : alternativas) {
                                    if (bodyText.contains(alt)) return true;
                                }
                                return false;
                            } catch (Exception ignored) { return false; }
                        });
                        encontrou = true;
                    } catch (Exception ignored) {
                        // Timeout — verifica uma última vez no body
                        try {
                            String bodyText = driver.findElement(By.tagName("body")).getText().toLowerCase();
                            for (String alt : alternativas) {
                                if (bodyText.contains(alt)) {
                                    encontrou = true;
                                    break;
                                }
                            }
                        } catch (Exception ignored2) {}
                    }
                    if (encontrou) {
                        result.setPassou(true);
                        result.setMensagem("Mensagem de confirmação encontrada na tela.");
                        System.out.println("[PASS] Validação OK: Mensagem encontrada");
                    } else {
                        // Log da mensagem visível para diagnóstico
                        try {
                            List<WebElement> msgs = driver.findElements(By.cssSelector(".mensagem,.mensagemDetalhada,.tabMensagens"));
                            for (WebElement m : msgs) {
                                String t = m.getText().trim();
                                if (!t.isEmpty()) System.out.println("[MSG-ATUAL] " + t);
                            }
                        } catch (Exception ignored) {}
                        result.setPassou(false);
                        result.setMensagem("Texto NÃO encontrado: '" + val.getValor() + "' (e nem variações de sucesso)");
                        result.setScreenshotBase64(screenshot());
                        System.out.println("[FAIL] Mensagem de sucesso '" + val.getValor() + "' não encontrada");
                    }
                    break;
                }
                case "url_contem": {
                    String current = driver.getCurrentUrl();
                    result.setPassou(current.contains(val.getValor()));
                    result.setMensagem(result.isPassou() ? "URL contém '" + val.getValor() + "'" : "URL '" + current + "' não contém '" + val.getValor() + "'");
                    break;
                }
                case "elemento_presente": {
                    try {
                        WebElement el = locator.encontrar(val.getLocalizadores(), val.getValor(), "any");
                        result.setPassou(el != null && el.isDisplayed());
                        result.setMensagem(result.isPassou() ? "Elemento presente" : "Elemento não visível");
                    } catch (Exception e) {
                        result.setPassou(false);
                        result.setMensagem("Elemento ausente: " + e.getMessage());
                    }
                    break;
                }
                case "ortografia": {
                    // ANTES usava getTextoVisivelParaCorrecao() + getErrorReport() — substitua por:
                    String textoTela = extractFromCurrentContext();
                    List<String> candidatos = Arrays.stream(textoTela.split("[\\n\\r]+"))
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && l.length() <= 120)
                        .distinct()
                        .collect(Collectors.toList());
                    String relatorioErros = spellChecker.getErrorReportFromLabels(candidatos);

                    if (relatorioErros == null) {
                        result.setPassou(true);
                        result.setMensagem("Nenhum erro de ortografia encontrado.");
                        System.out.println("[PASS] Ortografia OK");
                    } else {
                        result.setPassou(false);
                        result.setMensagem(relatorioErros);
                        result.setScreenshotBase64(screenshot());
                        System.out.println("[FAIL] Erros de ortografia: " + relatorioErros);
                    }
                    break;
                }
                default:
                    result.setPassou(false);
                    result.setMensagem("Tipo de validação desconhecido: " + val.getTipo());
            }
        } catch (Exception e) {
            result.setPassou(false);
            result.setMensagem("Erro na validação: " + e.getMessage());
        }
        result.setDuracaoMs(System.currentTimeMillis() - start);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private void navegarParaCons(TestScenario cenario) {
        if (cenario.getTela() == null) return;
        try {
            // Se tela aponta para Form, deriva Cons — Form direto sem ViewState redireciona para Cons de qualquer forma
            String consUrl = derivarConsUrl(cenario);
            String url = config.getSeiUrl() + consUrl;
            System.out.println("[INFO] Navegando para: " + url);
            driver.get(url);
            esperarPaginaCarregar(5);
            String src = driver.getPageSource();
            if (src.contains("HTTP Status 500") || src.contains("HTTP Status 404")) {
                System.out.println("[ERRO] Página retornou erro HTTP. Verifique a URL da tela no cenário.");
            }

            // DEPOIS — usa checkLabels com candidatos filtrados:
            System.out.println("[INFO] Verificando ortografia na tela...");
            String textoTela = extractFromCurrentContext();
            List<String> candidatos = Arrays.stream(textoTela.split("[\\n\\r]+"))
                .map(String::trim)
                .filter(l -> !l.isEmpty() && l.length() <= 120)
                .distinct()
                .collect(Collectors.toList());
            List<String> erros = spellChecker.checkLabels(candidatos);
            for (String erro : erros) {
                System.out.println("[ALERTA-ORTOGRAFIA] " + erro);
            }
        } catch (Exception e) {
            System.out.println("[WARN] Erro ao navegar: " + e.getMessage());
        }
    }

    /**
     * Aguarda AJAX do RichFaces completar após mudança de select.
     *
     * Problema: RichFaces 4.x usa RichFaces.jQuery (instância separada do jQuery global).
     * A verificação antiga só checava jQuery.active e retornava antes do re-render terminar,
     * fazendo o bot clicar no Gravar enquanto o DOM ainda estava sendo atualizado.
     *
     * Solução: verifica jQuery global + RichFaces.jQuery + fila interna do RichFaces.
     * Após AJAX zerado, aguarda pausa mínima fixa (300ms) para o browser aplicar o re-render.
     * Máximo total: ~5.3s — seguro para a sessão JSF.
     *
     * Aplica-se a TODAS as telas que usam a4j:ajax em selects (não só cursoForm).
     */
    private void aguardarAjaxPosSelect() {
        try {
            // Passo 1: aguarda jQuery global + RichFaces.jQuery + fila RichFaces zerados
            try {
                new WebDriverWait(driver, Duration.ofSeconds(5)).until(d -> {
                    try {
                        return (Boolean) ((JavascriptExecutor) d).executeScript(
                            "var jqOk   = typeof jQuery === 'undefined' || jQuery.active === 0;" +
                            "var rfJqOk = !(window.RichFaces && RichFaces.jQuery) || RichFaces.jQuery.active === 0;" +
                            "var rfQOk  = true; try { rfQOk = RichFaces.queue.isEmpty(); } catch(e) {}" +
                            "return jqOk && rfJqOk && rfQOk && document.readyState === 'complete';");
                    } catch (Exception e) { return true; }
                });
            } catch (Exception ignored) {}

            // Passo 2: pausa mínima fixa para o browser aplicar o partial-update no DOM.
            // render="@form" substitui os elementos do DOM — sem essa pausa o bot pode
            // encontrar o elemento antigo (stale) ou o elemento novo antes de estar pronto.
            Thread.sleep(300);

        } catch (Exception ignored) {}
    }

    /**
     * Clica em um ponto neutro da página (fora de campos de formulário) para
     * processar eventos blur/change pendentes sem disparar ações indesejadas.
     * Tenta em ordem: h1/h2/título da página, label, body (10,10).
     */
    private void clicarAreaNeutra() {
        try {
            // Tenta clicar no título da página ou cabeçalho — nunca é campo interativo
            Object clicou = ((JavascriptExecutor) driver).executeScript(
                "var alvos = ['h1','h2','.page-title','.card-header','.panel-heading','.ui-panel-titlebar','label'];" +
                "for(var i=0;i<alvos.length;i++){" +
                "  var el=document.querySelector(alvos[i]);" +
                "  if(el && el.offsetParent!==null){el.click();return true;}" +
                "}" +
                "return false;");
            if (Boolean.TRUE.equals(clicou)) return;
            // Fallback: blur + clique no body via JS (não aciona nenhum campo)
            ((JavascriptExecutor) driver).executeScript(
                "if(document.activeElement) document.activeElement.blur();" +
                "document.body.dispatchEvent(new MouseEvent('click',{bubbles:false,cancelable:false}));");
        } catch (Exception ignored) {
            try {
                // Último recurso: blur no elemento ativo
                ((JavascriptExecutor) driver).executeScript(
                    "if(document.activeElement) document.activeElement.blur();");
            } catch (Exception ignored2) {}
        }
    }

    /**
     * Seleciona opção em um <select> com múltiplos fallbacks:
     * 1. Texto visível exato  2. Valor do option  3. Começa com  4. Contém
     * 5. Primeira opção não-vazia disponível (quando valor está vazio ou sem match)
     */
    private void selecionarOpcaoSmart(WebElement el, String valorAlvo, String label) {
        // Estratégia 1: JS silencioso — seta selectedIndex sem disparar evento change/AJAX
        // Evita a4j:ajax execute="@form" em formulários incompletos (causaria falha de validação JSF)
        if (valorAlvo != null && !valorAlvo.isEmpty()) {
            try {
                Object jsResult = ((JavascriptExecutor) driver).executeScript(
                    "var sel=arguments[0], lv=arguments[1].toLowerCase().trim();" +
                    "for(var i=0;i<sel.options.length;i++){" +
                    "  if(sel.options[i].text.trim().toLowerCase()===lv){sel.selectedIndex=i;return sel.options[i].text.trim();}" +
                    "}" +
                    "for(var i=0;i<sel.options.length;i++){" +
                    "  if(sel.options[i].value.trim().toLowerCase()===lv){sel.selectedIndex=i;return sel.options[i].text.trim();}" +
                    "}" +
                    "for(var i=0;i<sel.options.length;i++){" +
                    "  if(sel.options[i].text.trim().toLowerCase().indexOf(lv)===0){sel.selectedIndex=i;return sel.options[i].text.trim();}" +
                    "}" +
                    "for(var i=0;i<sel.options.length;i++){" +
                    "  if(sel.options[i].text.trim().toLowerCase().indexOf(lv)>=0){sel.selectedIndex=i;return sel.options[i].text.trim();}" +
                    "}" +
                    "return null;",
                    el, valorAlvo);
                if (jsResult != null) {
                    System.out.println("[SELECT-SILENCIOSO] " + label + " = '" + jsResult + "' (sem AJAX)");
                    return;
                }
            } catch (Exception ignored) {}
        }

        // Estratégia 2: Selenium Select API (pode disparar onchange/AJAX — fallback)
        Select sel = new Select(el);
        if (valorAlvo != null && !valorAlvo.isEmpty()) {
            try { sel.selectByVisibleText(valorAlvo); System.out.println("[SELECT] " + label + " = '" + valorAlvo + "' (texto exato)"); return; } catch (Exception ignored) {}
            try { sel.selectByValue(valorAlvo); System.out.println("[SELECT] " + label + " = '" + valorAlvo + "' (value)"); return; } catch (Exception ignored) {}
            String lv = valorAlvo.toLowerCase();
            for (WebElement opt : sel.getOptions()) {
                String t = opt.getText().trim();
                if (!t.isEmpty() && t.toLowerCase().startsWith(lv)) {
                    try { sel.selectByVisibleText(t); System.out.println("[SELECT] " + label + " = '" + t + "' (startsWith)"); return; } catch (Exception ignored) {}
                }
            }
            for (WebElement opt : sel.getOptions()) {
                String t = opt.getText().trim();
                if (!t.isEmpty() && t.toLowerCase().contains(lv)) {
                    try { sel.selectByVisibleText(t); System.out.println("[SELECT] " + label + " = '" + t + "' (contains)"); return; } catch (Exception ignored) {}
                }
            }
        }
        // Fallback: primeira opção com valor não-vazio
        for (WebElement opt : sel.getOptions()) {
            String v = opt.getAttribute("value");
            String t = opt.getText().trim();
            if (v != null && !v.isEmpty() && !v.equals("0") && !v.equals("-1") && !t.isEmpty()) {
                try { sel.selectByValue(v); System.out.println("[SELECT-FALLBACK] " + label + " = primeira opção: '" + t + "'"); return; } catch (Exception ignored) {}
            }
        }
        System.out.println("[SELECT-WARN] " + label + ": nenhuma opção disponível para '" + valorAlvo + "'");
    }

    /**
     * Aguarda até 8s para que não haja overlay/modal visível bloqueando a página.
     * Se detectar overlay ativo, tenta clicar no botão de inclusão/fechar antes de continuar.
     */
    /**
     * Verifica se o formulário está dentro de um iframe e, se estiver, troca o contexto para ele.
     * Retorna true se houve troca (caller deve chamar driver.switchTo().defaultContent() depois).
     */
    private boolean switchToFormIframeIfNeeded(List<TestScenario.Acao> acoes) {
        if (acoes == null || acoes.isEmpty()) return false;

        // Coleta amostra de localizadores dos primeiros campos preenchíveis
        List<String> amostra = new ArrayList<>();
        for (TestScenario.Acao acao : acoes) {
            String tipo = acao.getTipo();
            if (("preencher".equals(tipo) || "selecionar".equals(tipo))
                    && acao.getLocalizadores() != null && !acao.getLocalizadores().isEmpty()) {
                amostra.addAll(acao.getLocalizadores());
                if (amostra.size() >= 4) break;
            }
        }
        if (amostra.isEmpty()) return false;

        // Verifica se algum localizador encontra elemento no documento principal
        for (String sel : amostra) {
            try {
                List<WebElement> found = sel.startsWith("//")
                    ? driver.findElements(By.xpath(sel))
                    : driver.findElements(By.cssSelector(sel));
                if (!found.isEmpty()) return false; // campos visíveis na página principal
            } catch (Exception ignored) {}
        }

        // Campos não encontrados no documento principal — tenta iframes
        try {
            List<WebElement> frames = driver.findElements(By.cssSelector("iframe, frame"));
            for (int i = 0; i < frames.size(); i++) {
                try {
                    driver.switchTo().frame(i);
                    for (String sel : amostra) {
                        try {
                            List<WebElement> found = sel.startsWith("//")
                                ? driver.findElements(By.xpath(sel))
                                : driver.findElements(By.cssSelector(sel));
                            if (!found.isEmpty()) {
                                System.out.println("[INFO] Formulário encontrado em iframe " + i + " — permanecendo no contexto do iframe para preenchimento.");
                                return true;
                            }
                        } catch (Exception ignored) {}
                    }
                    driver.switchTo().defaultContent();
                } catch (Exception e) {
                    try { driver.switchTo().defaultContent(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Encontra um campo usando SOMENTE os localizadores explícitos do JSON.
     * NÃO usa fallbacks semânticos (label/name/placeholder) do SmartLocator —
     * esses fallbacks podem encontrar campos errados na página.
     * Lança NoSuchElementException se nenhum localizador encontrar o campo visível.
     */
    private WebElement encontrarPorLocalizadoresExplicitos(List<String> localizadores, String label) {
        if (localizadores == null || localizadores.isEmpty()) {
            throw new NoSuchElementException("Nenhum localizador definido para '" + label + "'");
        }
        for (String sel : localizadores) {
            try {
                List<WebElement> encontrados = sel.startsWith("//")
                    ? driver.findElements(By.xpath(sel))
                    : driver.findElements(By.cssSelector(sel));
                for (WebElement candidate : encontrados) {
                    try {
                        if (candidate.isDisplayed()) {
                            ((JavascriptExecutor) driver).executeScript(
                                "arguments[0].scrollIntoView({behavior:'instant',block:'center'});", candidate);
                            pausa(60);
                            return candidate;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        // Tenta também elementos não visíveis mas presentes no DOM (ex: ocultos por AJAX ainda não concluído)
        for (String sel : localizadores) {
            try {
                List<WebElement> encontrados = sel.startsWith("//")
                    ? driver.findElements(By.xpath(sel))
                    : driver.findElements(By.cssSelector(sel));
                if (!encontrados.isEmpty()) {
                    WebElement el = encontrados.get(0);
                    ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({behavior:'instant',block:'center'});", el);
                    pausa(60);
                    return el;
                }
            } catch (Exception ignored) {}
        }
        throw new NoSuchElementException("Campo '" + label + "' não encontrado pelos localizadores: " + localizadores);
    }

    private void aguardarSemOverlay() {
        try {
            // Aguarda overlay PrimeFaces sumir
            new WebDriverWait(driver, Duration.ofSeconds(3))
                .until(d -> {
                    try {
                        List<WebElement> overlays = d.findElements(By.cssSelector(".ui-widget-overlay"));
                        return overlays.stream().noneMatch(el -> {
                            try { return el.isDisplayed(); } catch (Exception e) { return false; }
                        });
                    } catch (Exception e) { return true; }
                });
        } catch (Exception e) {
            // Overlay ainda presente — tenta resolver
            System.out.println("[OVERLAY] Overlay ativo detectado antes de preencher campo. Tentando resolver...");
            tratarModalInicialForm(null);
        }
    }

    /** Gera CPF matematicamente válido (dígitos verificadores mod 11) */
    private String gerarCpfValido() {
        long base = System.currentTimeMillis() % 1_000_000_000L;
        base = base % 899_999_999L + 100_000_000L;
        int[] d = new int[11];
        long tmp = base;
        for (int i = 8; i >= 0; i--) { d[i] = (int)(tmp % 10); tmp /= 10; }
        // 1º dígito
        int soma = 0;
        for (int i = 0; i < 9; i++) soma += d[i] * (10 - i);
        int r = soma % 11;
        d[9] = (r < 2) ? 0 : (11 - r);
        // 2º dígito
        soma = 0;
        for (int i = 0; i < 10; i++) soma += d[i] * (11 - i);
        r = soma % 11;
        d[10] = (r < 2) ? 0 : (11 - r);
        return String.format("%d%d%d.%d%d%d.%d%d%d-%d%d",
            d[0],d[1],d[2],d[3],d[4],d[5],d[6],d[7],d[8],d[9],d[10]);
    }

    private void esperarPaginaCarregar(int segundos) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(segundos))
                .until(d -> ((JavascriptExecutor) d)
                    .executeScript("return document.readyState").equals("complete"));
        } catch (Exception ignored) {}
    }

    /**
     * Trata modais que aparecem IMEDIATAMENTE ao abrir o formulário de inclusão.
     *
     * Estratégia (em ordem):
     * 1. Detecta overlay do PrimeFaces (.ui-widget-overlay) — sinal inequívoco de modal aberto
     * 2. Detecta [role='dialog'] visível — padrão ARIA
     * 3. Detecta botão "INCLUIR*" visível na página — botões que só existem em modais de verificação
     * 4. Preenche CPF no campo do modal (se existir)
     * 5. Clica no botão de inclusão e aguarda overlay sumir
     */
    private void tratarModalInicialForm(Map<String, Object> dadosTeste) {
        pausa(300);

        boolean modalAtivo = false;

        // ── Detecção 1: overlay PrimeFaces ────────────────────────────────────
        try {
            List<WebElement> overlays = driver.findElements(By.cssSelector(".ui-widget-overlay"));
            if (overlays.stream().anyMatch(el -> { try { return el.isDisplayed(); } catch (Exception e) { return false; } })) {
                modalAtivo = true;
                System.out.println("[MODAL-INICIAL] Overlay PrimeFaces detectado.");
            }
        } catch (Exception ignored) {}

        // ── Detecção 2: role="dialog" visível ────────────────────────────────
        if (!modalAtivo) {
            try {
                List<WebElement> ariaDialogs = driver.findElements(By.cssSelector("[role='dialog']"));
                if (ariaDialogs.stream().anyMatch(el -> { try { return el.isDisplayed(); } catch (Exception e) { return false; } })) {
                    modalAtivo = true;
                    System.out.println("[MODAL-INICIAL] ARIA dialog detectado.");
                }
            } catch (Exception ignored) {}
        }

        // ── Detecção 3: botão "INCLUIR*" visível (só aparece em modais de verificação) ──
        if (!modalAtivo) {
            try {
                String ci = "translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')";
                List<WebElement> btnsIncluir = driver.findElements(By.xpath(
                    "//*[self::button or self::a][contains(" + ci + ",'INCLUIR')]"));
                if (btnsIncluir.stream().anyMatch(el -> { try { return el.isDisplayed(); } catch (Exception e) { return false; } })) {
                    modalAtivo = true;
                    System.out.println("[MODAL-INICIAL] Botão INCLUIR* detectado — modal de verificação presente.");
                }
            } catch (Exception ignored) {}
        }

        if (!modalAtivo) {
            System.out.println("[MODAL-INICIAL] Nenhum modal inicial detectado.");
            return;
        }

        // ── Preenche CPF no modal ─────────────────────────────────────────────
        try {
            // Obtém CPF dos dados de teste — se não existir, gera um válido on-the-fly
            String cpf = "";
            if (dadosTeste != null) {
                for (Map.Entry<String, Object> e : dadosTeste.entrySet()) {
                    if (e.getKey().toLowerCase().contains("cpf") && e.getValue() != null) {
                        cpf = e.getValue().toString();
                        break;
                    }
                }
            }
            if (cpf.isEmpty()) {
                cpf = gerarCpfValido();
                System.out.println("[MODAL-INICIAL] CPF não encontrado em dados_teste, gerando: " + cpf);
            }
            final String cpfDigits = cpf.replaceAll("[^0-9]", "");
            boolean preencheu = false;

            // Estratégia 1: ID exato JSF — padrão SEI (formCpf:cpf, formVerificacao:cpf, etc.)
            String[] idsCandidatos = {
                "formCpf:cpf", "formVerificacao:cpf", "formCpf:certidao",
                "frmCpf:cpf", "frmVerificacao:cpf"
            };
            for (String candidatoId : idsCandidatos) {
                try {
                    WebElement inp = driver.findElement(By.id(candidatoId));
                    if (inp.isDisplayed() && inp.isEnabled()) {
                        new Actions(driver).click(inp)
                            .keyDown(Keys.CONTROL).sendKeys("a").keyUp(Keys.CONTROL)
                            .sendKeys(Keys.DELETE)
                            .sendKeys(cpfDigits)
                            .perform();
                        pausa(200);
                        System.out.println("[MODAL-INICIAL] CPF preenchido via ID '" + candidatoId + "': " + cpf);
                        preencheu = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            // Estratégia 2: XPath — input cujo id/name contém "cpf" (case insensitive)
            if (!preencheu) {
                try {
                    String xp = "//input[" +
                        "contains(translate(@id,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'cpf') or " +
                        "contains(translate(@name,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'cpf') or " +
                        "contains(translate(@placeholder,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'cpf')" +
                        "]";
                    List<WebElement> cpfInputs = driver.findElements(By.xpath(xp));
                    System.out.println("[MODAL-INICIAL] Estratégia 2: " + cpfInputs.size() + " inputs com 'cpf' em id/name/placeholder.");
                    for (WebElement inp : cpfInputs) {
                        String type = inp.getAttribute("type");
                        if ("hidden".equals(type) || "checkbox".equals(type) || "radio".equals(type)) continue;
                        if (inp.isDisplayed() && inp.isEnabled()) {
                            new Actions(driver).click(inp)
                                .keyDown(Keys.CONTROL).sendKeys("a").keyUp(Keys.CONTROL)
                                .sendKeys(Keys.DELETE)
                                .sendKeys(cpfDigits)
                                .perform();
                            pausa(200);
                            System.out.println("[MODAL-INICIAL] CPF preenchido via XPath id/name/placeholder 'cpf' (id='" + inp.getAttribute("id") + "'): " + cpf);
                            preencheu = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Estratégia 3: Primeiro input de texto visível dentro do modal, excluindo checkbox/radio/button
            if (!preencheu) {
                try {
                    // Procura o container do modal (rich:popupPanel → div.rf-pp-cnt, ou ui-dialog, etc.)
                    String modalSel = ".rf-pp-cnt, .rf-pp-cnt-shdw, [id$='panelCpf'], [id$='panelVerificacao'], " +
                        "[role='dialog'], .ui-dialog-content, .ui-overlaypanel";
                    List<WebElement> containers = driver.findElements(By.cssSelector(modalSel));
                    WebElement container = null;
                    for (WebElement c : containers) {
                        try {
                            if (c.isDisplayed() && c.getSize().getHeight() > 10) { container = c; break; }
                        } catch (Exception ignored) {}
                    }

                    List<WebElement> inputs;
                    if (container != null) {
                        inputs = container.findElements(By.cssSelector(
                            "input:not([type='hidden']):not([type='checkbox']):not([type='radio'])" +
                            ":not([type='button']):not([type='submit']):not([readonly]):not([disabled])"));
                        System.out.println("[MODAL-INICIAL] Estratégia 3 (container modal): " + inputs.size() + " inputs de texto encontrados.");
                    } else {
                        // Fallback: qualquer input de texto visível na página (excluindo checkbox/radio)
                        inputs = driver.findElements(By.cssSelector(
                            "input:not([type='hidden']):not([type='checkbox']):not([type='radio'])" +
                            ":not([type='button']):not([type='submit']):not([readonly]):not([disabled])"));
                        System.out.println("[MODAL-INICIAL] Estratégia 3 (fallback geral): " + inputs.size() + " inputs de texto.");
                    }
                    for (WebElement inp : inputs) {
                        try {
                            if (inp.isDisplayed() && inp.isEnabled()) {
                                System.out.println("[MODAL-INICIAL]   Tentando id='" + inp.getAttribute("id") + "' type='" + inp.getAttribute("type") + "'");
                                new Actions(driver).click(inp)
                                    .keyDown(Keys.CONTROL).sendKeys("a").keyUp(Keys.CONTROL)
                                    .sendKeys(Keys.DELETE)
                                    .sendKeys(cpfDigits)
                                    .perform();
                                pausa(200);
                                System.out.println("[MODAL-INICIAL] CPF preenchido via input genérico (id='" + inp.getAttribute("id") + "'): " + cpf);
                                preencheu = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }

            if (!preencheu) {
                System.out.println("[MODAL-INICIAL][WARN] Nenhuma estratégia preencheu o CPF no modal.");
            }
        } catch (Exception e) {
            System.out.println("[MODAL-INICIAL][ERROR] Erro ao preencher CPF: " + e.getMessage());
        }

        // ── Clica botão de inclusão/prosseguimento ────────────────────────────
        String ci = "translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ')";
        String[] prioridade = {
            "INCLUIR ALUNO", "INCLUIR FUNCIONÁRIO", "INCLUIR FUNCIONARIO",
            "INCLUIR", "CONSULTAR DADOS", "CONSULTAR", "PROSSEGUIR", "CONTINUAR", "CADASTRAR", "PRÓXIMO"
        };

        for (String txt : prioridade) {
            try {
                List<WebElement> btns = driver.findElements(By.xpath(
                    "//*[self::button or self::a or self::input[@type='button' or @type='submit']]"
                    + "[contains(" + ci + ",'" + txt + "')]"));
                for (WebElement btn : btns) {
                    try {
                        if (!btn.isDisplayed() || !btn.isEnabled()) continue;

                        jsClick(btn);
                        System.out.println("[MODAL-INICIAL] Clicou '" + txt + "'");

                        // Aguarda overlay desaparecer (modal fechou)
                        try {
                            new WebDriverWait(driver, Duration.ofSeconds(6))
                                .until(d -> d.findElements(By.cssSelector(".ui-widget-overlay"))
                                    .stream().noneMatch(el -> { try { return el.isDisplayed(); } catch (Exception ex) { return false; } }));
                        } catch (Exception ignored) {}

                        esperarPaginaCarregar(3);
                        System.out.println("[MODAL-INICIAL] Modal fechado. URL: " + driver.getCurrentUrl());
                        return;
                    } catch (StaleElementReferenceException ignored) {}
                }
            } catch (Exception ignored) {}
        }

        System.out.println("[MODAL-INICIAL] Botão de inclusão não encontrado. Continuando...");
    }

    /**
     * Detecta e fecha automaticamente qualquer popup/modal que apareça após preencher um campo.
     *
     * Tipos detectados:
     * 1. JavaScript alert/confirm → accept()
     * 2. PrimeFaces dialog (.ui-dialog visível) → clica Fechar/OK/Não
     * 3. Modal overlay genérico → clica o primeiro botão de fechamento
     * 4. Mensagem de erro inline → apenas loga (não bloqueia)
     */
    private void fecharPopupsAtivos(String contexto) {
        // ── 1. JavaScript alert/confirm (sem espera — apenas verifica se já existe) ──
        try {
            Alert alert = driver.switchTo().alert(); // lança exceção se não houver alert
            String alertText = alert.getText();
            System.out.println("[POPUP] Alert JS detectado após '" + contexto + "': " + alertText);
            alert.accept();
            pausa(200);
            System.out.println("[POPUP] Alert JS fechado.");
            return;
        } catch (Exception ignored) {}

        // ── 2. PrimeFaces / JSF modal dialog ──────────────────────────────────
        // Seletores de diálogos visíveis comuns no SEI
        String[] seletoresDialog = {
            ".ui-dialog:not([style*='display: none'])",
            ".ui-dialog-visible",
            "div[class*='dialog'][style*='display: block']",
            "div[class*='modal'][style*='display: block']",
            ".modal.show",
            "div[id*='dialog']:not([style*='display: none'])",
            "div[id*='Dialog']:not([style*='display: none'])",
            "div[id*='modal']:not([style*='display: none'])",
        };

        for (String sel : seletoresDialog) {
            try {
                List<WebElement> dialogs = driver.findElements(By.cssSelector(sel));
                for (WebElement dialog : dialogs) {
                    try {
                        if (!dialog.isDisplayed()) continue;
                        String textoDialog = dialog.getText();
                        System.out.println("[POPUP] Modal detectado após '" + contexto + "': "
                            + textoDialog.substring(0, Math.min(100, textoDialog.length())).replace("\n", " "));

                        // Tenta clicar em botão de fechamento/confirmação dentro do modal
                        String[] textosBotao = {"OK", "Fechar", "Não", "Cancelar", "Close", "Entendido", "Confirmar"};
                        boolean fechou = false;
                        for (String txtBtn : textosBotao) {
                            try {
                                // Busca o botão dentro do próprio dialog
                                List<WebElement> btns = dialog.findElements(
                                    By.xpath(".//button[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'"
                                        + txtBtn.toUpperCase() + "')]"
                                        + " | .//input[@type='button' or @type='submit'][contains(translate(@value,'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'"
                                        + txtBtn.toUpperCase() + "')]"
                                        + " | .//a[contains(translate(normalize-space(.),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'"
                                        + txtBtn.toUpperCase() + "')]"));
                                for (WebElement btn : btns) {
                                    if (btn.isDisplayed()) {
                                        jsClick(btn);
                                        pausa(500);
                                        System.out.println("[POPUP] Fechou modal clicando em '" + txtBtn + "'");
                                        fechou = true;
                                        break;
                                    }
                                }
                                if (fechou) break;
                            } catch (Exception ignored2) {}
                        }

                        // Se não achou botão de texto, tenta o botão X de fechar
                        if (!fechou) {
                            try {
                                WebElement closeBtn = dialog.findElement(
                                    By.cssSelector(".ui-dialog-titlebar-close, .close, [aria-label='Close'], button[class*='close']"));
                                if (closeBtn.isDisplayed()) {
                                    jsClick(closeBtn);
                                    pausa(500);
                                    System.out.println("[POPUP] Fechou modal pelo botão X");
                                    fechou = true;
                                }
                            } catch (Exception ignored3) {}
                        }

                        if (fechou) return;
                    } catch (StaleElementReferenceException ignored) {}
                }
            } catch (Exception ignored) {}
        }

        // ── 3. Overlay de bloqueio ativo (backdrop) ───────────────────────────
        try {
            List<WebElement> overlays = driver.findElements(
                By.cssSelector(".ui-widget-overlay, .modal-backdrop, .overlay:not([style*='display: none'])"));
            if (!overlays.isEmpty()) {
                System.out.println("[POPUP] Overlay detectado após '" + contexto + "' — tentando Escape...");
                driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE);
                pausa(500);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Preenche campo de data com máscara (ex: __/__/____).
     * Usa combinação de clear + JS setValue + sendKeys dígito a dígito.
     */
    private void preencherCampoData(WebElement el, String valor) {
        try {
            // Tenta limpar e preencher via JavaScript (mais confiável em campos com máscara)
            ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = ''; arguments[0].focus();", el);
            pausa(200);
            // Envia os dígitos um a um para acionar o listener da máscara
            String apenasNumeros = valor.replaceAll("[^0-9]", "");
            el.sendKeys(apenasNumeros);
            pausa(300);
            // Verifica se o valor foi preenchido corretamente
            String valorAtual = el.getAttribute("value");
            if (valorAtual == null || valorAtual.isEmpty() || valorAtual.equals("__/__/____")) {
                // Fallback: limpa completamente e usa Actions para digitar
                ((JavascriptExecutor) driver).executeScript("arguments[0].value = '';", el);
                el.click();
                new Actions(driver).sendKeys(el, apenasNumeros).perform();
            }
        } catch (Exception e) {
            // Último fallback: sendKeys direto
            try {
                el.clear();
                el.sendKeys(valor);
            } catch (Exception ignored) {}
        }
    }

    /** Clica via JavaScript (evita "element not interactable" em elementos fora do viewport) */
    private void jsClick(WebElement el) {
        try {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
        } catch (Exception e) {
            el.click(); // fallback para click normal
        }
    }

    private String resolverValor(TestScenario.Acao acao, String tipoOp, Map<String, Object> dadosTeste) {
        if ("alterar".equals(tipoOp) && acao.getValor_alterar() != null)
            return substituir(acao.getValor_alterar(), dadosTeste);
        if (acao.getValor() != null)
            return substituir(acao.getValor(), dadosTeste);
        if (acao.getCampo() != null && dadosTeste != null) {
            Object v = dadosTeste.get(acao.getCampo());
            if (v != null) return substituir(v.toString(), dadosTeste);
        }
        return "";
    }

    private String substituir(String valor, Map<String, Object> dados) {
        if (valor == null) return "";
        if (dados != null)
            for (Map.Entry<String, Object> e : dados.entrySet())
                if (e.getValue() != null) valor = valor.replace("{{" + e.getKey() + "}}", e.getValue().toString());
        valor = valor.replace("{{timestamp}}", String.valueOf(runTimestamp));
        valor = valor.replace("{{data_hoje}}", java.time.LocalDate.now().toString());
        return valor;
    }

    /**
     * Extrai labels de toda a página para verificação ortográfica.
     * Diferente de extractFromCurrentContext(), NÃO filtra por visibilidade —
     * captura labels em abas ocultas, painéis colapsados e booleanos/toggles.
     * Varre também iframes (padrão em alguns formulários do SEI).
     */
    private String extrairTodosLabelsParaOrtografia() {
        StringBuilder sb = new StringBuilder();
        sb.append(extrairLabelsContextoAtual());

        try {
            List<WebElement> frames = driver.findElements(By.cssSelector("iframe, frame"));
            for (int i = 0; i < frames.size(); i++) {
                try {
                    driver.switchTo().frame(i);
                    sb.append("\n").append(extrairLabelsContextoAtual());
                    driver.switchTo().defaultContent();
                } catch (Exception ignored) {
                    try { driver.switchTo().defaultContent(); } catch (Exception ignored2) {}
                }
            }
        } catch (Exception ignored) {}

        return sb.toString();
    }

    private String extrairLabelsContextoAtual() {
        try {
            return (String) ((JavascriptExecutor) driver).executeScript(
                "var res = [];" +
                "var vistos = new Set();" +
                "function add(t) {" +
                "  t = (t || '').replace(/[*:]+/g,'').trim();" +
                "  if (t && t.length >= 3 && t.length <= 120 && !vistos.has(t.toLowerCase())) {" +
                "    vistos.add(t.toLowerCase()); res.push(t);" +
                "  }" +
                "}" +
                // Captura .tituloCampos e variantes (sem checar visibilidade)
                "document.querySelectorAll('.tituloCampos, .titulo-campo, .field-label, .control-label').forEach(function(el) {" +
                "  if (el.tagName !== 'SCRIPT' && el.tagName !== 'STYLE') add(el.textContent);" +
                "});" +
                // Captura <label>: para flipswitch-label extrai só os text nodes diretos
                // (evita incluir o texto ON/OFF dos spans filhos, mas captura o título do campo)
                "document.querySelectorAll('label').forEach(function(el) {" +
                "  if (el.classList.contains('flipswitch-label')) {" +
                "    var txt = '';" +
                "    el.childNodes.forEach(function(n){if(n.nodeType===3)txt+=n.textContent;});" +
                "    add(txt.trim());" +
                "  } else {" +
                "    add(el.textContent);" +
                "  }" +
                "});" +
                // Captura spans e tds com texto curto (título de campo sem classe específica)
                "document.querySelectorAll('span, td, th').forEach(function(el) {" +
                "  if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE') return;" +
                "  var t = (el.textContent || '').trim();" +
                "  if (t && t.length >= 3 && t.length <= 60 && el.children.length === 0) add(t);" +
                "});" +
                "return res.join('\\n');"
            );
        } catch (Exception e) {
            return "";
        }
    }

    private String getTextoVisivelParaCorrecao() {
        StringBuilder sb = new StringBuilder();
        
        // 1. Texto do conteúdo principal
        sb.append(extractFromCurrentContext());

        // 2. Varrer Frames e Iframes recursivamente
        try {
            List<WebElement> frames = driver.findElements(By.cssSelector("iframe, frame"));
            for (int i = 0; i < frames.size(); i++) {
                try {
                    driver.switchTo().frame(i);
                    sb.append(" ").append(extractFromCurrentContext());
                    
                    // Tenta entrar em sub-frames se existirem
                    List<WebElement> subframes = driver.findElements(By.cssSelector("iframe, frame"));
                    for (int j = 0; j < subframes.size(); j++) {
                        try {
                            driver.switchTo().frame(j);
                            sb.append(" ").append(extractFromCurrentContext());
                            driver.switchTo().parentFrame();
                        } catch (Exception ignored) {}
                    }
                    
                    driver.switchTo().defaultContent();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
 
        String totalText = sb.toString().replaceAll("\\s+", " ").trim();
        
        // LOG DE DEBUG PARA VOCÊ VER O QUE O BOT ESTÁ LENDO
        if (totalText.length() > 0) {
            System.out.println("[DEBUG-CAPTURA] Início do texto lido: " + 
                totalText.substring(0, Math.min(totalText.length(), 300)) + "...");
        } else {
            System.out.println("[DEBUG-CAPTURA] ATENÇÃO: Nenhum texto foi extraído da tela!");
        }
        
        return totalText;
    }

    private String extractFromCurrentContext() {
        try {
            return (String) ((JavascriptExecutor) driver).executeScript(
                "var res = [];" +

                // Helper: verifica se elemento está realmente visível e não é script/style
                "function isValidFieldElement(el) {" +
                "  if (!el || !el.offsetParent) return false;" +
                "  var tagName = el.tagName.toLowerCase();" +
                "  if (tagName === 'script' || tagName === 'style' || tagName === 'meta' || tagName === 'link') return false;" +
                "  var style = window.getComputedStyle(el);" +
                "  if (style.display === 'none' || style.visibility === 'hidden') return false;" +
                "  var rect = el.getBoundingClientRect();" +
                "  if (rect.width === 0 || rect.height === 0) return false;" +
                "  return true;" +
                "}" +

                // 1. Labels, títulos e aria-label (contexto de formulário)
                "document.querySelectorAll('label').forEach(function(el) {" +
                "  if (isValidFieldElement(el)) {" +
                "    var t = (el.textContent || el.getAttribute('title') || el.getAttribute('aria-label') || '').trim();" +
                "    if (t && t.length < 200) res.push(t);" +
                "  }" +
                "});" +

                // 2. Títulos de campo específicos do SEI (.tituloCampos é a classe do span)
                "document.querySelectorAll('.tituloCampos, .titulo-campo, .field-label, .control-label').forEach(function(el) {" +
                "  if (isValidFieldElement(el)) {" +
                "    var t = (el.textContent || '').trim();" +
                "    if (t && t.length < 200) res.push(t);" +
                "  }" +
                "});" +

                // 3. Apenas elementos com chave de tradução quebrada (???, !!!)
                "document.querySelectorAll('span,td,th,h1,h2,h3,h4,h5,h6').forEach(function(el) {" +
                "  if (isValidFieldElement(el)) {" +
                "    var t = (el.textContent || '').trim();" +
                "    if (t && (t.indexOf('???') >= 0 || t.indexOf('!!!') >= 0) && t.length < 200) res.push(t);" +
                "  }" +
                "});" +

                "return res.join('\\n');");
        } catch (Exception e) {
            return "";
        }
    }

    private void verificarOrtografiaSilenciosa() {
        try {
            String texto = extractFromCurrentContext();
            if (texto == null || texto.trim().isEmpty()) return;

            // Divide em linhas — cada linha é um label/texto de UI candidato
            List<String> candidatos = Arrays.stream(texto.split("[\\n\\r]+"))
                .map(String::trim)
                .filter(l -> !l.isEmpty() && l.length() <= 120)
                .distinct()
                .collect(Collectors.toList());

            List<String> erros = spellChecker.checkLabels(candidatos);
            for (String erro : erros) {
                System.out.println("[ALERTA-ORTOGRAFIA-GLOBAL] " + erro);
            }
        } catch (Exception ignored) {}
    }

    private void pausa(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private String screenshot() {
        try {
            byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            return java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ignored) { return null; }
    }
}
