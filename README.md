# Teclado IA 🎤⌨️

Teclado Android **100% gratuito** em português (pt-BR) com:

1. **Ditado por voz em tempo real** — o texto aparece enquanto você fala.
   - Online: reconhecimento de voz do Android (`SpeechRecognizer`, resultados parciais, pt-BR).
   - Offline: [Vosk](https://alphacephei.com/vosk/) com o modelo `vosk-model-small-pt-0.3` (baixado dentro do app, ~31 MB).
   - Escolhe sozinho: online quando há internet, offline quando não há. Opção "Somente offline".
2. **Correção enquanto digita** — palavras erradas aparecem na barra de sugestões; toque para aplicar.
   - Offline: SymSpell (portado para Kotlin) + dicionário de frequência pt-BR com 50 mil palavras.
   - Online: [LanguageTool](https://languagetool.org) API pública gratuita (gramática/concordância), após ~0,8 s de pausa, respeitando o limite gratuito (no máx. 1 pedido a cada 4 s).
   - Autocorreção ao apertar espaço (opcional). Backspace logo depois desfaz.

Sem anúncios, sem conta, sem APIs pagas.

## Instalar

Baixe o APK em **Releases** (`teclado-ia-v0.1.0-debug.apk`).

1. Abra o APK no celular e permita **instalar de fontes desconhecidas** quando o Android pedir.
2. Abra o app **Teclado IA**.
3. Toque em **Abrir Idiomas e entrada** e ative **Teclado IA** (Configurações > Sistema > Idiomas e entrada > Teclado na tela > Gerenciar teclados).
4. Toque em **Escolher Teclado IA como teclado atual**.
5. Toque em **Permitir microfone**.
6. (Para voz sem internet) Toque em **Baixar modelo offline**.

## Teclado

- QWERTY pt-BR com tecla **ç**.
- Segure uma letra para acentos: a → á ã â à, e → é ê, i → í, o → ó õ ô, u → ú ü, c → ç. Letras da fila de cima têm números.
- ⇧ uma vez = maiúscula; duas vezes rápido = Caps Lock. Maiúscula automática no início da frase.
- `?123` números e símbolos, `=\<` mais símbolos.
- Segure o espaço para trocar de teclado. Dois espaços = ponto final.

## Compilar

```bash
./gradlew assembleDebug
```

O GitHub Actions (`.github/workflows/android.yml`) compila o APK debug a cada push na `main`, envia como artefato e anexa à Release `v0.1.0`.

Stack: Kotlin, `InputMethodService`, minSdk 24, targetSdk 34, sem AndroidX (só Vosk + JNA como dependências).

## Licenças de terceiros

- **Dicionário** `app/src/main/assets/pt_br_50k.txt`: [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) — código sob MIT; conteúdo sob **CC BY-SA 4.0** (derivado de legendas do OpenSubtitles). Atribuição: Hermit Dave.
- **SymSpell**: algoritmo de Wolf Garbe, [MIT](https://github.com/wolfgarbe/SymSpell). Implementação própria em Kotlin.
- **Vosk** (`com.alphacephei:vosk-android`) e modelo `vosk-model-small-pt-0.3`: Apache 2.0, Alpha Cephei.
- **JNA**: Apache 2.0 / LGPL 2.1.
- **LanguageTool**: serviço público gratuito (https://api.languagetool.org). O texto só é enviado quando a correção online está ligada e há internet.

## Privacidade

- Modo offline: nada sai do celular.
- Modo online: o áudio vai para o serviço de voz do Android (Google, conforme o aparelho) e o texto digitado (até 600 caracteres antes do cursor) para o LanguageTool. Campos de senha, e-mail e URL nunca são enviados nem corrigidos.
