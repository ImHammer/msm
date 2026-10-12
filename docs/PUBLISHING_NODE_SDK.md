# 📦 Guia de Publicação do SDK Node.js no NPM (`@imhammer/msm-node`)

Este documento serve como referência rápida para o ciclo de releases do SDK `@imhammer/msm-node`. O documento oficial detalhado do SDK também está localizado em [`packages/msm-node/docs/PUBLISHING.md`](../packages/msm-node/docs/PUBLISHING.md).

---

## ⚡ Sequência de Comandos para Atualização

No diretório do pacote (`packages/msm-node` ou no repositório separado `msm-node`):

```bash
# 1. Garantir que os testes locais passam
npm test

# 2. Commitar as alterações
git add .
git commit -m "feat: descrição da melhoria ou correção"

# 3. Incrementar a versão (escolha um):
npm version patch   # Bug fix (ex: 1.0.1 -> 1.0.2)
# npm version minor # Nova funcionalidade (ex: 1.0.1 -> 1.1.0)
# npm version major # Breaking change (ex: 1.0.1 -> 2.0.0)

# 4. Publicar a versão atualizada no NPM
npm publish --access public

# 5. Enviar tags e commits para o GitHub
git push origin main --tags

# 6. Validar a nova versão
npm view @imhammer/msm-node version
```

---

## 📌 Requisitos de Autenticação
Certifique-se de que seu arquivo `~/.npmrc` contém um token de automação ou token granular válido:
```ini
//registry.npmjs.org/:_authToken=SEU_TOKEN_DO_NPM
```
Para mais detalhes e solução de problemas, consulte [`packages/msm-node/docs/PUBLISHING.md`](../packages/msm-node/docs/PUBLISHING.md).
