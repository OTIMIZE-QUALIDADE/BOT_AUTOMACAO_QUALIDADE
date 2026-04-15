package br.com.otimize.qualidade;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.apache.commons.cli.*;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class MainApp {

    public static void main(String[] args) {
        Options opts = new Options();
        opts.addOption("m", "mode", true, "Modo: executar (padrão) | scan");
        opts.addOption("c", "cenario", true, "Cenário ID");
        opts.addOption("f", "file", true, "Caminho do arquivo JSON do cenário");
        opts.addOption("u", "url", true, "URL base do SEI");
        opts.addOption("t", "tela", true, "URL relativa da tela (para modo scan)");
        opts.addOption("user", "usuario", true, "Usuário SEI");
        opts.addOption("pass", "senha", true, "Senha SEI");
        opts.addOption("o", "operacoes", true, "Operações: inserir,alterar,excluir");
        opts.addOption("r", "relatorio", true, "Caminho do relatório / cenário gerado");
        opts.addOption("l", "locators", true, "Caminho do arquivo locators.json");
        opts.addOption("d", "descricao", true, "Descrição do cenário (para modo scan)");
        opts.addOption("mod", "modulo", true, "Módulo SEI (para modo scan)");
        opts.addOption("headless", "headless", false, "Rodar Chrome em modo headless (sem janela)");
        opts.addOption("abas", "abas", true, "Nomes das abas separados por vírgula (override do auto-detect)");

        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(opts, args);
        } catch (ParseException e) {
            System.out.println("[ERRO] Argumentos inválidos: " + e.getMessage());
            System.exit(1);
            return;
        }

        String modo = cmd.getOptionValue("m", "executar");
        String seiUrl = cmd.getOptionValue("u", "");
        String usuario = cmd.getOptionValue("user", "");
        String senha = cmd.getOptionValue("pass", "");

        Config config = new Config(seiUrl, usuario, senha);

        boolean headless = cmd.hasOption("headless");

        // ── MODO SCAN ──────────────────────────────────────────────────────────────
        if ("scan".equals(modo)) {
            String telaUrl = cmd.getOptionValue("t", "");
            String cenarioId = cmd.getOptionValue("c", "cenario-gerado");
            String descricao = cmd.getOptionValue("d", "Cenário gerado automaticamente");
            String modulo = cmd.getOptionValue("mod", "Geral");
            String saida = cmd.getOptionValue("r", cenarioId + ".json");
            String abasManual = cmd.getOptionValue("abas", "");

            System.out.println("[INFO] Modo: SCAN");
            System.out.println("[INFO] Tela: " + telaUrl);
            if (headless) System.out.println("[INFO] Modo headless ativado.");
            if (!abasManual.isEmpty()) System.out.println("[INFO] Abas manuais: " + abasManual);

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
            WebDriver driver = new ChromeDriver(options);

            try {
                ScreenScanner scanner = new ScreenScanner(driver, config);
                scanner.escanear(telaUrl, cenarioId, descricao, modulo, saida, abasManual);
                System.out.println("[OK] Escaneamento concluído. Cenário salvo: " + saida);
            } finally {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                driver.quit();
            }
            return;
        }

        // ── MODO EXECUTAR ──────────────────────────────────────────────────────────
        String cenarioFile = cmd.getOptionValue("f");
        String operacoesStr = cmd.getOptionValue("o", "inserir,alterar,excluir");
        String relatorioPath = cmd.getOptionValue("r", "relatorio.json");
        String locatorsPath = cmd.getOptionValue("l", "locators.json");

        List<String> operacoes = Arrays.asList(operacoesStr.split(","));

        System.out.println("[INFO] Modo: EXECUTAR");
        System.out.println("[INFO] Carregando cenário: " + cenarioFile);

        ObjectMapper mapper = new ObjectMapper();
        TestScenario cenario;
        try {
            cenario = mapper.readValue(new File(cenarioFile), TestScenario.class);
        } catch (Exception e) {
            System.out.println("[ERRO] Falha ao carregar cenário: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            if (Files.exists(Paths.get(locatorsPath))) {
                mapper.readValue(new File(locatorsPath), Map.class); // validate
            }
        } catch (Exception e) {
            System.out.println("[WARN] Locators registry inválido, usando padrão.");
        }

        CenarioExecutor executor = new CenarioExecutor(config, locatorsPath, headless);
        List<StepResult> resultados = executor.executar(cenario, operacoes);

        ReportWriter writer = new ReportWriter();
        writer.salvar(relatorioPath, cenario, resultados);

        long falhas = resultados.stream().filter(r -> !r.isPassou()).count();
        System.out.println("[INFO] Total de etapas: " + resultados.size());
        System.out.println("[INFO] Passou: " + (resultados.size() - falhas));
        System.out.println("[INFO] Falhou: " + falhas);

        System.exit(falhas > 0 ? 1 : 0);
    }
}
