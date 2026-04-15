package br.com.otimize.qualidade;

public class Config {
    private final String seiUrl;
    private final String usuario;
    private final String senha;
    private int waitMs = 8000;
    private int pauseMs = 1500;

    public Config(String seiUrl, String usuario, String senha) {
        this.seiUrl = seiUrl.endsWith("/") ? seiUrl : seiUrl + "/";
        this.usuario = usuario;
        this.senha = senha;
    }

    public String getSeiUrl() { return seiUrl; }
    public String getUsuario() { return usuario; }
    public String getSenha() { return senha; }
    public int getWaitMs() { return waitMs; }
    public int getPauseMs() { return pauseMs; }
}
