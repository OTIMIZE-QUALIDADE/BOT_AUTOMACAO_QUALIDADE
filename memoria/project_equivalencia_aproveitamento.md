---
name: Mapa de Equivalência e Aproveitamento
description: Implementação do endpoint mapa-equivalencia e melhorias no aproveitamento (2024-03)
type: project
---

## O que foi implementado

### sei_M — MigracaoRS.java
- Novos imports: `MapaEquivalenciaMatrizCurricularVO`, `MapaEquivalenciaDisciplinaVO`, `MapaEquivalenciaDisciplinaMatrizCurricularVO`, `MapaEquivalenciaDisciplinaCursadaVO` + enums
- `case "MAPA_EQUIVALENCIA":` no `/campos/{tipo}` — metadados para mapeamento no conector
- `case "mapa-equivalencia":` no `despacharRegistro()` → `batchMapaEquivalencia()`
- `importarMapaEquivalenciaInterno()` + `importarMapaEquivalenciaLogic()` — lógica completa
- `@POST @Path("/mapa-equivalencia")` — endpoint público

### Lógica importarMapaEquivalenciaLogic
1. Lookup grade por `nomeGrade` + `nomeCurso`
2. Busca mapa existente via `getMapaEquivalenciaMatrizCurricularFacade().consultarPorCodigoGradeCurricular()`
3. Se não existe: cria `MapaEquivalenciaMatrizCurricularVO` com situação `EM_CONSTRUCAO` e persiste
4. Suporta dois formatos JSON:
   - **Flat**: campos `disciplinaDestino`, `disciplinaOrigem`, `cargaHorariaDestino/Origem`, regras
   - **Hierárquico**: campo `equivalencias[]` com arrays `disciplinasDestino[]` e `disciplinasOrigem[]`
5. Persiste via `getMapaEquivalenciaDisciplinaFacade().incluirMapaEquivalenciaDisciplinaVOs()`

### otimize-plataforma
- `servidor.py`: adicionado `"mapaequivalencia": "MAPA_EQUIVALENCIA"` em `ENTIDADE_MAP` e colunas em `COLUNAS_BOT`
- `index.html`: nav "Mapa Equivalência", botão no conector BD→BD, opção no select JSON→SEI, template e `ORDEM_MIGRACAO`
- `json-teste/mapa_equivalencias.json`: exemplos para CC e ADM

**Why:** Migração de sistemas legados requer importar os mapas de equivalência que definem quais disciplinas cursadas em outras instituições equivalem às da grade curricular do SEI.
