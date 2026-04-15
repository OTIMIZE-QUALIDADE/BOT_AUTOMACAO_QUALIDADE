package br.com.otimize.qualidade;

import java.util.List;

public class TestSpellChecker {
    public static void main(String[] args) {
        SpellChecker checker = new SpellChecker();
        String text = "Este é um exenplo de texto com erros de ortogafia e gramatica.";
        System.out.println("Testando texto: " + text);
        
        List<String> errors = checker.checkText(text);
        if (errors.isEmpty()) {
            System.out.println("Nenhum erro encontrado.");
        } else {
            System.out.println("Erros encontrados:");
            for (String error : errors) {
                System.out.println("- " + error);
            }
        }

        String report = checker.getErrorReport(text);
        System.out.println("\nRelatório final:");
        System.out.println(report);
    }
}
