---
name: Arquitetura dos sistemas
description: Visão geral da arquitetura do otimize-plataforma, sei_M e bots Java
type: project
---

## Sistemas envolvidos

- **otimize-plataforma** (`c:\Automatizadores\otimize-plataforma`): servidor Python (porta 9870), frontend HTML único, orquestra bots Java (Selenium) e conector-banco (Spring Boot porta 9878)
- **sei_M** (`c:\SEI\workspace\sei_M`): sistema SEI J2EE, API REST em `MigracaoRS.java` com base path `@Path("/migracao")`, filtro de token em `MigracaoTokenFilter.java`
- **aproveitamento** (`c:\Automatizadores\aproveitamento`): bot standalone Java (porta 9876), lê Excel, preenche formulários SEI via Selenium

## Padrões chave

- Endpoints SEI: `POST /rest/migracao/{entidade}` — autenticação Bearer token
- `campos/{tipo}` (GET): metadados de campos para UI de mapeamento
- batch dispatcher em `despacharRegistro()`: case por entidade → chama `batchX()` → `importarXInterno()` → `importarXLogic()`
- Helpers reutilizáveis: `lookupCursoPorNome()`, `lookupGradePorNomeECurso()`, `lookupDisciplinaPorNome()`
- JSON teste em `json-teste/` — modelos para cada entidade
- servidor.py: `ENTIDADE_MAP` (bot→SEI), `COLUNAS_BOT` (colunas Excel/mapeamento)
