---
name: Otimize Qualidade — Arquitetura e Tecnologias
description: Visão completa do projeto otimize-qualidade: estrutura, tecnologias usadas, como foram implementadas e padrões de design
type: project
---

## O que é

Ferramenta de automação de testes CRUD para o sistema SEI (telas `.xhtml` JSF).
Permite escanear qualquer tela do SEI e gerar um cenário de teste JSON, depois executá-lo automaticamente via Selenium.

**Porta:** 9871 (servidor Python)
**Inicialização:** `INICIAR.bat`

---

## Estrutura de pastas

```
otimize-qualidade/
├── INICIAR.bat                   # Ponto de entrada
├── servidor.py                   # Servidor HTTP Python porta 9871
├── baixar_runtime.py             # Baixa JDK 21 + Maven automaticamente
├── index.html                    # Frontend principal
├── cenarios.html                 # Gerenciamento de cenários
├── locators-default.json         # Seletores CSS/XPath padrão do SEI
├── cenarios/                     # JSONs de cenários (um por tela/entidade)
├── relatorios/                   # Relatórios JSON + HTML gerados
├── uploads/                      # Reservado para futuros uploads
├── runtime/                      # JDK, Maven, cache Maven
└── modulos/
    └── bot-qualidade/            # Bot Java com Selenium
        ├── pom.xml
        └── src/main/java/br/com/otimize/qualidade/
            ├── MainApp.java          # Entry point do JAR
            ├── Config.java           # URL, usuário, senha + timeouts (waitMs=8000)
            ├── TestScenario.java     # Modelo do cenário JSON
            ├── StepResult.java       # Resultado de cada etapa + screenshot
            ├── CenarioExecutor.java  # Executa CRUD no SEI
            ├── ScreenScanner.java    # Escaneia tela e gera cenário
            ├── SmartLocator.java     # 7 estratégias de localização de elementos
            ├── ScreenFingerprint.java# Detecta mudanças estruturais de tela
            └── ReportWriter.java     # Gera relatório JSON + HTML
```

---

## Tecnologias e como foram implementadas

### Backend — Python (`servidor.py`)

- **`http.server` puro** (sem frameworks): HTTPServer + BaseHTTPRequestHandler
- **SSE (Server-Sent Events)**: endpoint `/api/sse/{exec_id}` transmite log em tempo real ao browser, usando fila em memória (`sse_queues`) e polling a cada 300ms
- **subprocess.Popen**: o servidor lança o JAR Java como processo filho, captura stdout linha a linha e envia via SSE
- **Threading**: cada execução roda em thread separada para não bloquear o servidor
- **Compilação sob demanda**: se o JAR não existir, `compilar_bot()` executa `mvn clean package -q` automaticamente antes de rodar

### Frontend — HTML/JS (`index.html`)

- **Design system idêntico ao otimize-plataforma**: variáveis CSS GitHub dark (--bg, --bg2, --tx, --green, etc.), fontes IBM Plex Mono + Syne, topbar 50px, painéis e botões iguais
- **Tema claro/escuro**: toggle via `body.classList.toggle('light')`, persistido em `localStorage`
- **Toast notifications**: componente próprio, slide-in animation, 3 tipos (ok, err, info)
- **Modal de relatório**: exibe steps com badges coloridos por operação, sem usar `alert()`
- **SSE no browser**: `EventSource` consome o stream do servidor, popula o painel de log em tempo real
- **3 painéis**: esquerda (lista cenários), centro (tabs: executar/escanear/relatórios), direita (log)

### Bot Java (`bot-qualidade`)

- **Java 21** + **Maven 3** com fat JAR (`maven-assembly-plugin` + `jar-with-dependencies`)
- **Selenium 4.20** + **WebDriverManager 5.8**: gerencia o ChromeDriver automaticamente sem instalação manual
- **Jackson 2.17**: parse de cenário JSON para POJO (`TestScenario`, `Acao`, `Validacao`)
- **Apache Commons CLI 1.6**: parse de argumentos de linha de comando (`-m`, `-u`, `-user`, `-pass`, `-c`, etc.)
- **Apache POI 5.2.5**: dependência incluída para relatórios Excel futuros (não usado ainda)
- **SLF4J Simple**: logging leve

### SmartLocator — 7 estratégias em cascata

1. Seletores CSS/XPath da lista `localizadores` do cenário
2. Texto do `<label>` (XPath case-insensitive via `translate()`)
3. `placeholder` do input
4. `name`/`id` parcial (`input[id*='texto']`)
5. `aria-label`
6. Texto do botão com aliases (Salvar → Gravar, Excluir → Remover, Confirmar → SIM/OK)
7. Classe CSS do botão (padrão OTM: `a.btn-gravar`, `a.btn-excluir`, `[id$=':salvar']`)

Após encontrar → `scrollIntoView` automático.
Se tudo falhar → `diagnosticarTela()` imprime labels e botões disponíveis no console.

### ScreenScanner — 4 passos de escaneamento

1. Login no SEI, navega para tela Cons, captura `ScreenFingerprint`
2. Clica NOVO (tenta: Novo, Adicionar, Incluir, Cadastrar, Inserir)
3. Detecta URL mudou (tela_form) ou formulário inline
4. **Passo 1**: scan campos visíveis com dados fictícios automáticos
5. **Passo 2**: testa cada opção dos selects → detecta campos condicionais → deixa select no máximo de campos
6. **Passo 3**: re-scan com valores ótimos em ordem DOM
7. **Passo 4**: detecta abas (`rich:tabPanel`, Bootstrap) e escaneia campos por aba
8. Gera JSON do cenário completo

### CenarioExecutor — fluxo CRUD

- Login → navega Cons → clica NOVO → 3 estratégias (click/nav-direta/jsClick) → aguarda URL mudar
- `valorChaveInserido`: captura o valor do campo-chave no inserir para reusar no alterar/excluir
- `{{timestamp}}`: substituído por número de 5 dígitos fixo por execução (garante consistência)
- Fechamento de modais automáticos (panelCpf do SEI)
- Screenshot em base64 em cada falha

### ReportWriter — saída dupla

- **JSON** (`relatorios/relatorio-{exec_id}.json`): estruturado com steps, status, timestamps, screenshot_base64
- **HTML** (`relatorios/relatorio-{exec_id}.html`): dashboard visual dark, cards de resumo, tabela com badges coloridos, screenshots expansíveis via `<details>`

---

## Diferença vs otimize-plataforma

| | **otimize-plataforma** | **otimize-qualidade** |
|---|---|---|
| Objetivo | Migração de dados BD→SEI | Testes CRUD automatizados |
| Integração SEI | API REST `/rest/migracao/` | Interface web (Selenium) |
| Porta | 9870 | 9871 |
| Bot Java | Spring Boot conector-banco (9878) | Standalone fat JAR |
| Dados | Excel / BD relacional | Cenários JSON |

**Why:** O SEI não expõe endpoints de teste — a única forma de testar as telas é via automação de browser.

**How to apply:** Ao falar sobre testes do SEI, usar este projeto. Para migração de dados, usar otimize-plataforma.
