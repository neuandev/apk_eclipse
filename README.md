# Eclipse Nativo (Android)

App Android 100% nativo (Kotlin + Jetpack Compose) com frontend libretro em C++ (NDK).
Sem WebView e sem internet: os emuladores rodam como bibliotecas nativas dentro do app.

| Sistema | Núcleo (libretro) | Extensões |
|---|---|---|
| Game Boy / Color | Gambatte | .gb .gbc |
| Game Boy Advance | mGBA | .gba |
| NES | FCEUmm | .nes |
| Super Nintendo | Snes9x | .sfc .smc |

## Como compilar
1. Instale o **Android Studio** (com SDK 34, NDK e CMake 3.22 pelo SDK Manager).
2. Baixe os núcleos: `./scripts/fetch_cores.sh` (ou copie manualmente os `.so` arm64 do
   buildbot da libretro para `app/src/main/jniLibs/arm64-v8a/`, com os nomes
   `libmgba_libretro.so`, `libgambatte_libretro.so`, `libfceumm_libretro.so`, `libsnes9x_libretro.so`).
3. Abra a pasta no Android Studio, aguarde o sync do Gradle e rode em um aparelho arm64.

## O que tem
- **Biblioteca** com grade de capas, seção **Recentes** (até 10) e menu por jogo:
  Jogar, Definir Capa, Remover Capa, Adicionar/Remover dos Recentes, Renomear, Excluir.
- **Recentes:** todo jogo aberto entra automaticamente; também dá para adicionar à mão.
- **Capas:** qualquer imagem da galeria ou do app Arquivos (reduzida para 512 px).
- **Jogo:** controles na tela (multitouch), gamepad/teclado, 3 slots de estado salvo,
  save de bateria (.srm) e estado automático ao sair (retoma de onde parou).
- **Idioma:** português (Brasil) por padrão; inglês em `values-en`.

## Licenças
Os núcleos têm licenças próprias (mGBA: MPL 2.0; Gambatte e FCEUmm: GPL v2; Snes9x: licença não
comercial própria). Se for distribuir o app, respeite essas licenças. Use apenas ROMs que você tem direito de usar.

## Ideias para o futuro
Filtros de vídeo/shaders, fast-forward, opções dos núcleos, mais sistemas (Genesis, PS1...),
importação de .zip, backup de saves.
