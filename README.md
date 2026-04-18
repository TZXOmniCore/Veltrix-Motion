# GyroDrive — Controle Completo para Forza xCloud

Controle Xbox completo para o Xbox Cloud Gaming (xCloud) usando o giroscópio do celular como volante, sem root, sem hardware externo.

---

## Como funciona

```
Giroscópio do celular
       ↓
  GyroManager.kt         ← lê o sensor, aplica deadzone/suavização
       ↓
  OverlayService.kt      ← desenha o HUD flutuante sobre o Chrome
       ↓
  GyroDriveAccessibilityService.kt  ← injeta toques reais no xCloud via dispatchGesture()
```

O `AccessibilityService` é o único componente no Android capaz de injetar toques em outros apps sem root. A injeção usa `GestureDescription.dispatchGesture()` (API 24+).

---

## Setup no Android Studio

1. Clone este repositório
2. Abra no Android Studio Hedgehog ou mais recente
3. Conecte o celular via USB (USB Debugging ativo)
4. Run → `app`

---

## Ativação no celular (obrigatório)

### 1. Permissão de Overlay
```
Configurações → Apps → GyroDrive → Exibir sobre outros apps → Permitir
```

### 2. Serviço de Acessibilidade
```
Configurações → Acessibilidade → Apps instalados → GyroDrive Controller → Ativar
```
> ⚠ Confirme "Permitir" quando aparecer o aviso de privacidade do Android.

---

## Calibrar coordenadas do xCloud

Os botões virtuais do xCloud ficam em posições fixas na tela dependendo da resolução.
Os valores padrão são para **2400×1080** (landscape).

Se os toques estiverem errando, edite `XCloudTouchMap.default()` em `GyroDriveAccessibilityService.kt`:

```kotlin
// Descobrir coordenadas:
// adb shell → getevent -l  (toque na tela e veja as coordenadas)
// Ou: Configurações Dev → "Localização do ponteiro"
```

---

## Arquitetura dos arquivos

```
app/src/main/java/com/gyrodrive/
├── GyroDriveAccessibilityService.kt  ← ★ injeção de toque (núcleo)
├── GyroManager.kt                    ← leitura do giroscópio
├── OverlayService.kt                 ← HUD flutuante + foreground service
└── MainActivity.kt                   ← setup, permissões, configurações

app/src/main/res/
├── xml/accessibility_service_config.xml  ← declara canPerformGestures=true
├── values/strings.xml
└── values/styles.xml
```

---

## Requisitos

- Android 8.0+ (API 26) — necessário para `StrokeDescription.willContinue()`
- Giroscópio / rotation vector sensor
- Chrome instalado (para o xCloud)

---

## Fluxo de toque para o analógico esquerdo (direção)

O xCloud renderiza um joystick virtual na tela. Para "mover" esse joystick:

1. O `GyroManager` converte a inclinação do celular em um valor `-1.0` a `+1.0`
2. O loop de 60Hz no `GyroDriveAccessibilityService` calcula a posição alvo:
   ```
   targetX = leftStickCenterX + steerValue * leftStickRadius
   ```
3. Injeta um gesto de arrasto do centro até essa posição a cada 32ms

Para o RT/LT (triggers), injeta toques repetidos a cada 80ms enquanto o botão estiver pressionado no HUD.

---

## Limitações conhecidas

- Coordenadas precisam ser calibradas por resolução de tela
- xCloud pode mudar o layout do gamepad overlay com atualizações do Chrome
- Em alguns dispositivos o `TYPE_ACCESSIBILITY_OVERLAY` pode não sobrepor o Chrome corretamente → use `TYPE_APPLICATION_OVERLAY` como fallback
