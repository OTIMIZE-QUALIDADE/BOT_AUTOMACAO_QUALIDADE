package br.com.otimize.qualidade;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class StepResult {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String operacao;
    private final String descricao;
    private final String hora;
    private boolean passou;
    private String mensagem;
    private String screenshotBase64;
    private long duracaoMs;

    public StepResult(String operacao, String descricao) {
        this.operacao = operacao;
        this.descricao = descricao;
        this.hora = LocalDateTime.now().format(FMT);
    }

    public void setPassou(boolean passou) { this.passou = passou; }
    public void setMensagem(String mensagem) { this.mensagem = mensagem; }
    public void setScreenshotBase64(String s) { this.screenshotBase64 = s; }
    public void setDuracaoMs(long ms) { this.duracaoMs = ms; }

    public String getOperacao() { return operacao; }
    public String getDescricao() { return descricao; }
    public String getHora() { return hora; }
    public boolean isPassou() { return passou; }
    public String getMensagem() { return mensagem; }
    public String getScreenshotBase64() { return screenshotBase64; }
    public long getDuracaoMs() { return duracaoMs; }
}
