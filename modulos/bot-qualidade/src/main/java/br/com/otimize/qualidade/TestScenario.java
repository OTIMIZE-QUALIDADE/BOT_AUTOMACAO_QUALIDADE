package br.com.otimize.qualidade;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TestScenario {
    private String id;
    private String descricao;
    private String modulo;

    /**
     * URL da tela de consulta/listagem (Cons).
     * Ex: visaoAdministrativo/academico/alunoCons.xhtml
     * É aqui que o bot clica "Novo" para abrir o formulário.
     */
    private String tela;

    /**
     * URL da tela de formulário (Form), se diferente da tela Cons.
     * Ex: visaoAdministrativo/academico/alunoForm.xhtml
     * Preenchido automaticamente pelo scanner quando "Novo" redireciona para outra URL.
     * Se null, o formulário abre inline na mesma página da listagem.
     */
    private String tela_form;

    /**
     * Campo chave para busca no alterar/excluir.
     * Ex: "nome" — o bot buscará pelo valor desse campo na tela de listagem.
     */
    private String campo_chave;

    private List<String> operacoes_disponiveis;
    private OperacaoConfig inserir;
    private OperacaoConfig alterar;
    private OperacaoConfig excluir;
    private OperacaoConfig ortografia;
    private Map<String, Object> dados_teste;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public String getModulo() { return modulo; }
    public void setModulo(String modulo) { this.modulo = modulo; }
    public String getTela() { return tela; }
    public void setTela(String tela) { this.tela = tela; }
    public String getTela_form() { return tela_form; }
    public void setTela_form(String tela_form) { this.tela_form = tela_form; }
    public String getCampo_chave() { return campo_chave; }
    public void setCampo_chave(String campo_chave) { this.campo_chave = campo_chave; }
    public List<String> getOperacoes_disponiveis() { return operacoes_disponiveis; }
    public void setOperacoes_disponiveis(List<String> ops) { this.operacoes_disponiveis = ops; }
    public OperacaoConfig getInserir() { return inserir; }
    public void setInserir(OperacaoConfig inserir) { this.inserir = inserir; }
    public OperacaoConfig getAlterar() { return alterar; }
    public void setAlterar(OperacaoConfig alterar) { this.alterar = alterar; }
    public OperacaoConfig getExcluir() { return excluir; }
    public void setExcluir(OperacaoConfig excluir) { this.excluir = excluir; }
    public OperacaoConfig getOrtografia() { return ortografia; }
    public void setOrtografia(OperacaoConfig o) { this.ortografia = o; }
    public Map<String, Object> getDados_teste() { return dados_teste; }
    public void setDados_teste(Map<String, Object> dados_teste) { this.dados_teste = dados_teste; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OperacaoConfig {
        private List<Acao> acoes;
        private List<Validacao> validacoes;

        public List<Acao> getAcoes() { return acoes; }
        public void setAcoes(List<Acao> acoes) { this.acoes = acoes; }
        public List<Validacao> getValidacoes() { return validacoes; }
        public void setValidacoes(List<Validacao> validacoes) { this.validacoes = validacoes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Acao {
        private String tipo; // clicar, preencher, selecionar, limpar, buscar, confirmar_dialogo
        private String campo;
        private String valor;
        private String valor_alterar;
        private List<String> localizadores;
        private String label;
        private String texto_botao;
        /**
         * Para tipo "buscar": texto a digitar no campo de busca da tela Cons.
         * O bot preenche o campo de pesquisa e pressiona Enter/buscar.
         */
        private String buscar_por;
        /** Hint de tipo especial: "data" → campo com máscara de data */
        @JsonProperty("_tipo_campo")
        private String tipoCampoHint;
        /** Se true, o executor pula este campo graciosamente quando não encontrado/visível */
        private boolean condicional;
        /**
         * Se true, após preencher o campo dispara evento "change" via JS para acionar
         * AJAX listeners (ex: CEP → carregarEnderecoPessoa, código → buscar descrição).
         */
        private boolean dispara_change;
        /**
         * Se true, após clicar no botão aguarda AJAX/re-render completar antes de prosseguir.
         * Padrão: true para todos os botões não-Salvar (evita preencher antes do form estabilizar).
         */
        private boolean aguardar_ajax = true;
        /** Para clicar_aba: nome da aba a ser clicada (lido via label ou valor) */
        private String aba;

        public String getTipo() { return tipo; }
        public void setTipo(String tipo) { this.tipo = tipo; }
        public String getCampo() { return campo; }
        public void setCampo(String campo) { this.campo = campo; }
        public String getValor() { return valor; }
        public void setValor(String valor) { this.valor = valor; }
        public String getValor_alterar() { return valor_alterar; }
        public void setValor_alterar(String v) { this.valor_alterar = v; }
        public List<String> getLocalizadores() { return localizadores; }
        public void setLocalizadores(List<String> localizadores) { this.localizadores = localizadores; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getTexto_botao() { return texto_botao; }
        public void setTexto_botao(String texto_botao) { this.texto_botao = texto_botao; }
        public String getBuscar_por() { return buscar_por; }
        public void setBuscar_por(String buscar_por) { this.buscar_por = buscar_por; }
        @JsonProperty("_tipo_campo")
        public String getTipoCampo() { return tipoCampoHint; }
        public void setTipoCampo(String t) { this.tipoCampoHint = t; }
        public boolean isCondicional() { return condicional; }
        public void setCondicional(boolean condicional) { this.condicional = condicional; }
        public boolean isDispara_change() { return dispara_change; }
        public void setDispara_change(boolean dispara_change) { this.dispara_change = dispara_change; }
        public boolean isAguardar_ajax() { return aguardar_ajax; }
        public void setAguardar_ajax(boolean aguardar_ajax) { this.aguardar_ajax = aguardar_ajax; }
        public String getAba() { return aba; }
        public void setAba(String aba) { this.aba = aba; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Validacao {
        private String tipo; // mensagem_sucesso, elemento_presente, elemento_ausente, texto_contem, url_contem
        private String valor;
        private List<String> localizadores;

        public String getTipo() { return tipo; }
        public void setTipo(String tipo) { this.tipo = tipo; }
        public String getValor() { return valor; }
        public void setValor(String valor) { this.valor = valor; }
        public List<String> getLocalizadores() { return localizadores; }
        public void setLocalizadores(List<String> l) { this.localizadores = l; }
    }
}
