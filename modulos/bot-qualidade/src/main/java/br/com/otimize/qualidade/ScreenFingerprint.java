package br.com.otimize.qualidade;

import java.util.ArrayList;
import java.util.List;

public class ScreenFingerprint {
    private final String telaId;
    private final List<String> labels = new ArrayList<>();
    private final List<String> fields = new ArrayList<>();
    private final List<String> buttons = new ArrayList<>();

    public ScreenFingerprint(String telaId) {
        this.telaId = telaId;
    }

    public void addLabel(String label) { labels.add(label); }
    public void addField(String field) { fields.add(field); }
    public void addButton(String button) { buttons.add(button); }

    public String getTelaId() { return telaId; }
    public List<String> getLabels() { return labels; }
    public List<String> getFields() { return fields; }
    public List<String> getButtons() { return buttons; }

    public String toSummary() {
        return String.format("Tela '%s': %d labels, %d campos, %d botões",
            telaId, labels.size(), fields.size(), buttons.size());
    }

    /**
     * Compare with a previous fingerprint and detect changes.
     */
    public List<String> detectarMudancas(ScreenFingerprint anterior) {
        List<String> mudancas = new ArrayList<>();

        // Labels removed
        for (String lbl : anterior.getLabels()) {
            if (!this.labels.contains(lbl)) {
                mudancas.add("AVISO Label removida: '" + lbl + "'");
            }
        }
        // Labels added
        for (String lbl : this.labels) {
            if (!anterior.getLabels().contains(lbl)) {
                mudancas.add("NOVO Nova label: '" + lbl + "'");
            }
        }
        // Fields changed count
        if (this.fields.size() != anterior.getFields().size()) {
            mudancas.add("AVISO Numero de campos mudou: " + anterior.getFields().size() + " -> " + this.fields.size());
        }

        return mudancas;
    }
}
