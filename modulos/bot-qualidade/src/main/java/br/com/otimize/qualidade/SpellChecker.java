package br.com.otimize.qualidade;

import org.languagetool.JLanguageTool;
import org.languagetool.language.BrazilianPortuguese;
import org.languagetool.rules.RuleMatch;

import java.io.IOException;
import java.util.*;

/**
 * SpellChecker - Valida ortografia de labels de campos de UI do SEI.
 *
 * ESTRATÉGIA:
 * - Recebe lista de labels já extraídos (não texto bruto da tela)
 * - Checa cada label individualmente para evitar falsos positivos de repetição
 * - Palavras em MAIÚSCULAS são verificadas letra a letra (detecta "NOMIS", "CODGIO" etc.)
 * - Whitelist protege siglas legítimas do SEI/domínio
 */
public class SpellChecker {

    private final JLanguageTool langTool;

    // Siglas e termos técnicos válidos no contexto do SEI e do sistema
    private final Set<String> whitelist = new HashSet<>(Arrays.asList(
        // Siglas institucionais
        "CBO", "QA", "DEV", "RH", "TI", "SEI", "OTIMIZE", "OTM",
        // Documentos
        "CPF", "CNPJ", "RG", "PIS", "NIT", "CNH",
        // Técnico
        "ENUM", "MSG", "BSSP", "URL", "CEP", "UF",
        // Termos em inglês aceitos no sistema
        "LOGIN", "EMAIL", "STATUS",
        // Abreviações comuns em formulários
        "OBS", "NR", "NRO", "QTD", "DT"
    ));

    // Erros críticos conhecidos: mapeamento exato de palavra errada → sugestão correta
    // Adicione aqui qualquer erro reportado que o LanguageTool não pega
    private final Map<String, String> errosCriticosConhecidos = new LinkedHashMap<>();

    public SpellChecker() {
        this.langTool = new JLanguageTool(new BrazilianPortuguese());

        // Erros já reportados que escapam do LanguageTool
        errosCriticosConhecidos.put("nomis", "Nome");
        errosCriticosConhecidos.put("rejustada", "Reajustada");
        errosCriticosConhecidos.put("deeerpatamento", "Departamento");
        errosCriticosConhecidos.put("departameto", "Departamento");
        errosCriticosConhecidos.put("codigo", "Código");      // sem acento
        errosCriticosConhecidos.put("situaco", "Situação");
        errosCriticosConhecidos.put("descricao", "Descrição"); // sem acento
        errosCriticosConhecidos.put("adevertencia", "Advertência");  // 'e' extra antes do 'v'
        errosCriticosConhecidos.put("adevertência", "Advertência");  // variante com acento
    }

    /**
     * Verifica uma lista de labels de campos de UI.
     * Este é o método principal — receba labels já extraídos, não texto bruto da tela.
     *
     * @param labels lista de strings como ["Nome do Aluno", "Data de Nascimento", "NOMIS"]
     * @return lista de erros encontrados
     */
    public List<String> checkLabels(List<String> labels) {
        List<String> errors = new ArrayList<>();
        if (labels == null || labels.isEmpty()) return errors;

        Set<String> jaReportados = new HashSet<>(); // evita duplicatas entre labels

        for (String label : labels) {
            if (label == null || label.trim().isEmpty()) continue;
            List<String> errosLabel = checkLabel(label.trim());
            for (String erro : errosLabel) {
                if (!jaReportados.contains(erro)) {
                    errors.add(erro);
                    jaReportados.add(erro);
                }
            }
        }
        return errors;
    }

    /**
     * Verifica um único label de campo.
     * Divide em tokens e verifica cada palavra individualmente.
     */
    public List<String> checkLabel(String label) {
        List<String> errors = new ArrayList<>();
        if (label == null || label.trim().isEmpty()) return errors;

        // 1. Normaliza: remove asteriscos, dois-pontos (comuns em labels de formulário)
        String normalizado = label.replaceAll("[*:]", "").trim();

        // 2. Detecta padrão de chave de tradução quebrada (???CHAVE??? ou !!!chave!!!)
        if (normalizado.matches(".*([?!]{3})[^?!\\s]+([?!]{3}).*")) {
            errors.add(String.format("FALHA DE TRADUÇÃO: \"%s\"", normalizado));
            return errors; // label completamente inválido, não verifica ortografia
        }

        // 3. Verifica cada token (palavra) individualmente
        String[] tokens = normalizado.split("[\\s/\\-]+");
        for (String token : tokens) {
            if (token.isEmpty()) continue;

            // Remove pontuação final (parênteses, ponto final etc.)
            String palavra = token.replaceAll("[().,;!?]+$", "").replaceAll("^[().,;!?]+", "");
            if (palavra.isEmpty() || palavra.length() < 2) continue;

            // Verifica erro crítico conhecido (case-insensitive)
            String chaveCritica = palavra.toLowerCase();
            if (errosCriticosConhecidos.containsKey(chaveCritica)) {
                String sugestao = errosCriticosConhecidos.get(chaveCritica);
                errors.add(String.format(
                    "ERRO CRÍTICO DE UI: \"%s\" no label \"%s\". Sugestão: %s",
                    palavra, label, sugestao));
                continue; // não verifica também pelo LanguageTool
            }

            // Whitelist: siglas e termos técnicos válidos
            if (whitelist.contains(palavra.toUpperCase())) continue;

            // Ignora: números, tokens com números misturados (ex: "2024", "H2O", "v3")
            if (palavra.matches(".*\\d.*")) continue;

            // Ignora: tokens muito curtos em maiúsculas que são provavelmente siglas
            if (palavra.length() <= 3 && palavra.equals(palavra.toUpperCase())) continue;

            // 4. Para palavras em MAIÚSCULAS (ex: "NOMIS", "CODGIO"):
            //    converte para Title Case antes de checar — LanguageTool lida melhor
            //    Ex: "NOMIS" → "Nomis" → LanguageTool detecta como palavra desconhecida
            String paraChecar = ehMaiusculas(palavra)
                ? capitalize(palavra.toLowerCase())
                : palavra;

            // 5. Verifica com LanguageTool
            List<String> errosLT = verificarComLanguageTool(paraChecar, palavra, label);
            errors.addAll(errosLT);
        }

        return errors;
    }

    /**
     * Verifica uma palavra isolada com o LanguageTool.
     * Envolve a palavra em uma frase simples para dar contexto ao parser.
     */
    private List<String> verificarComLanguageTool(String paraChecar, String palavraOriginal, String labelOriginal) {
        List<String> errors = new ArrayList<>();

        // Envolve em frase simples — LanguageTool funciona melhor com contexto de frase
        // do que com palavras isoladas (evita falsos positivos de "fragmento de frase")
        String frase = "O campo " + paraChecar + " é obrigatório.";

        try {
            List<RuleMatch> matches = langTool.check(frase);
            for (RuleMatch match : matches) {
                // Captura apenas o trecho que corresponde à nossa palavra (ignora "O campo" e "é obrigatório")
                String trecho = frase.substring(
                    Math.max(0, match.getFromPos()),
                    Math.min(frase.length(), match.getToPos())
                ).trim();

                // Ignora se o erro está fora da palavra que queremos checar
                if (!trecho.equalsIgnoreCase(paraChecar) && !trecho.equalsIgnoreCase(palavraOriginal)) continue;

                // Ignora regras gramaticais que não são erro de ortografia de UI
                // (ex: concordância com artigo, crase, etc.)
                String ruleId = match.getRule().getId();
                if (ruleId.startsWith("AGREEMENT_") || ruleId.startsWith("CRASE_")
                        || ruleId.startsWith("COMMA_") || ruleId.equals("UPPERCASE_SENTENCE_START")) continue;

                List<String> sugestoes = match.getSuggestedReplacements();
                String sugestao = sugestoes.isEmpty() ? "sem sugestão" : sugestoes.get(0);

                errors.add(String.format(
                    "ERRO ORTOGRÁFICO: \"%s\" no label \"%s\". Sugestão: %s",
                    palavraOriginal, labelOriginal, sugestao));
            }
        } catch (IOException e) {
            System.err.println("[ERRO] LanguageTool falhou para '" + paraChecar + "': " + e.getMessage());
        }

        return errors;
    }

    /**
     * Verifica texto bruto (compatibilidade com código legado).
     * ATENÇÃO: prefira checkLabels() para resultados mais precisos.
     * Este método tenta extrair tokens significativos antes de verificar.
     */
    public List<String> checkText(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();

        // Divide em linhas e filtra linhas que parecem labels de campo
        // (curtas, sem HTML, não repetitivas)
        List<String> labelsExtraidos = new ArrayList<>();
        Set<String> vistos = new HashSet<>();

        for (String linha : text.split("[\\n\\r]+")) {
            String l = linha.trim().replaceAll("[*:]", "").trim();

            // Ignora linhas vazias, muito longas (provavelmente parágrafo de texto) ou com HTML
            if (l.isEmpty() || l.length() > 80 || l.contains("<") || l.contains(">")) continue;

            // Ignora linhas com repetição de mesma palavra (ex: "Editar Editar Editar")
            if (temRepeticaoExcessiva(l)) continue;

            // Ignora se já vimos esse label
            if (vistos.contains(l.toLowerCase())) continue;
            vistos.add(l.toLowerCase());

            labelsExtraidos.add(l);
        }

        return checkLabels(labelsExtraidos);
    }

    /**
     * Detecta repetição excessiva de tokens (ex: "Editar Editar Editar").
     * Retorna true se a mesma palavra aparece mais de 2x seguidas ou no total.
     */
    private boolean temRepeticaoExcessiva(String texto) {
        String[] tokens = texto.trim().split("\\s+");
        if (tokens.length < 3) return false;

        // Conta frequência de cada token
        Map<String, Integer> freq = new HashMap<>();
        for (String t : tokens) {
            String tl = t.toLowerCase();
            freq.merge(tl, 1, Integer::sum);
        }

        // Se qualquer token aparece mais de 2x → repetição excessiva
        for (int count : freq.values()) {
            if (count > 2) return true;
        }

        // Detecta padrão "A B A B A B" (repetição cíclica)
        if (tokens.length >= 4) {
            boolean todosPares = true;
            for (int i = 0; i < tokens.length - 2; i++) {
                if (!tokens[i].equalsIgnoreCase(tokens[i + 2])) {
                    todosPares = false;
                    break;
                }
            }
            if (todosPares) return true;
        }

        return false;
    }

    /** Retorna relatório formatado ou null se não houver erros. */
    public String getErrorReport(String text) {
        List<String> errors = checkText(text);
        if (errors.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Erros de ortografia/gramática encontrados:\n");
        for (String error : errors) sb.append("- ").append(error).append("\n");
        return sb.toString();
    }

    /** Retorna relatório formatado para uma lista de labels. */
    public String getErrorReportFromLabels(List<String> labels) {
        List<String> errors = checkLabels(labels);
        if (errors.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Erros de ortografia/gramática encontrados:\n");
        for (String error : errors) sb.append("- ").append(error).append("\n");
        return sb.toString();
    }

    private boolean ehMaiusculas(String s) {
        return s.equals(s.toUpperCase()) && s.chars().anyMatch(Character::isLetter);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}