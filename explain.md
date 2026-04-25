# autoDarkMode 構成説明メモ

このドキュメントは、**Kotlin は分かるが Kotlin Multiplatform（KMP）は未経験**という前提で、このリポジトリを読み始めるための案内です。

対象は現行実装です。結論から言うと、このプロジェクトは次の形になっています。

- 共有したいロジックは `src/commonMain/` に置く
- macOS 固有の実装は `src/macosMain/` と `src/macosArm64Main/` に置く
- AppKit / Foundation / POSIX / IOKit などのネイティブ API は Kotlin/Native から直接呼ぶ
- private framework や C ヘッダが絡む部分は `src/nativeInterop/cinterop/` でブリッジする
- ビルドの入口はルートの `build.gradle.kts` と `Scripts/*.sh`
を置いた Kotlin/Native ベースの macOS メニューバーアプリです。

KMP 未経験でも、まずは **「共通ロジック」と「macOS 実装」を分けているだけ** と捉えると読みやすくなります。そのうえで、必要になったら source set の継承階層を理解するとスムーズです。
## 1. まず全体像

このアプリは macOS のメニューバー常駐アプリです。やっていることは大きく 4 つです。

1. 周囲光センサーから現在の明るさを読む
2. 設定値を保存・復元する
3. ルールに従って Light / Dark を切り替える
4. メニューバー UI / 設定ウィンドウ / Launch at login を提供する

KMP の観点では、**純粋な設定ロジックを共有ソースセットへ寄せ、macOS 固有部分はネイティブ実装として分離**している構成です。

---

## 2. ルートのファイル構成と役割

### ビルド関連

- `build.gradle.kts`
  - KMP の中心設定です。
  - `kotlin("multiplatform")` を有効化し、`macosArm64` と `macosX64` を定義しています。
  - `macosArm64` には `cinterops.create("ambientlight")` が定義されており、`src/nativeInterop/cinterop/ambientlight.def` を使って C ヘッダを Kotlin/Native に見せています。
  - 実行可能バイナリの entry point は `com.github.gutugutu3030.autodarkmode.app.main` です。

- `settings.gradle.kts`
  - Gradle のルートプロジェクト名だけを定義しています。

- `gradlew`, `gradlew.bat`, `gradle/`
  - Gradle wrapper 一式です。

### 実行・検証スクリプト

- `Scripts/build-app.sh`
  - 実体は薄いラッパーで、`Scripts/build-kotlin-app.sh` を呼びます。

- `Scripts/build-kotlin-app.sh`
  - `linkReleaseExecutableMacosArm64` または `linkDebugExecutableMacosArm64` を実行し、
    `build/bin/macosArm64/.../*.kexe` を作ります。
  - その後 `AppResources/Info.plist` を使って `dist/autoDarkMode.app` を組み立てます。

- `Scripts/validate.sh`
  - 標準の検証入口です。
  - `./gradlew check linkDebugExecutableMacosArm64` と app bundle 作成をまとめて実行します。

### アプリリソース

- `AppResources/Info.plist`
  - macOS app bundle 用の `Info.plist` テンプレートです。
  - `LSUIElement=true` なので Dock には常駐せず、メニューバーアプリとして動きます。

### ドキュメント

- `README.md`
  - ユーザー向けの入口です。ビルド方法、切り替えモード、CLI サンプル方法などがまとまっています。

- `docs/kotlin-multiplatform/`
  - 移行検討や設計メモです。現行実装の補助資料として読む位置づけです。

### 生成物・編集対象外のことが多い場所

- `build/`
  - Gradle / Kotlin/Native の生成物です。
  - `build/bin/...` に `.kexe`、`build/test-results/` にテスト結果、`build/kotlinProjectStructureMetadata/` に source set 情報が出ます。

- `dist/`
  - `Scripts/build-app.sh` 実行後の `.app` バンドル出力先です。

- `.gradle/`, `.kotlin/`, `.build/`
  - ツールのキャッシュや中間生成物です。通常は手で編集しません。

---

## 3. `src/` 配下の見方

`src/` は KMP の source set ごとに分かれています。

```text
src/
  commonMain/
  commonTest/
  macosMain/
  macosTest/
  macosArm64Main/
  macosArm64Test/
  nativeInterop/
```

KMP 未経験だとまず混乱しやすいのは、**「どのコードがどこまで共通なのか」**です。ざっくり次の理解で読むと追いやすいです。

- `commonMain`: どのプラットフォームでも使える共有 Kotlin コード
- `macosMain`: macOS 共通コード
- `macosArm64Main`: Apple Silicon macOS 実行ファイル向けコード
- `nativeInterop`: C ヘッダや `.def` を置く Kotlin/Native 用ブリッジ設定
- `*Test`: 各層に対応するテスト

---

## 4. source set をこのリポジトリでどう使っているか

### 4-1. `commonMain`

場所:

- `src/commonMain/kotlin/com/github/gutugutu3030/autodarkmode/shared/`

役割:

- 設定値モデル
- 保存キーをまたいだ整合性制御
- しきい値の clamp
- 旧設定からの migration
- `StateFlow` での状態公開

主なファイル:

- `KeyValueStore.kt`
  - 永続化先の抽象インターフェースです。
  - ここでは `NSUserDefaults` を知らず、文字列・数値・真偽値の読み書きだけ定義します。

- `SwitchMode.kt`
  - `Off / Auto / Manual` の共有 enum です。

- `SettingsStoreState.kt`
  - 設定スナップショットと、有効しきい値・有効サンプル数などの算出ロジックを持ちます。

- `SettingsStoreLogic.kt`
  - `KeyValueStore` の上に乗る共有ロジックの中心です。
  - 保存キー管理、初期値、legacy migration、値の制約、現在値ボタン向け更新などを担います。

重要ポイント:

- ここは **「macOS API を知らない純 Kotlin ロジック」** の層です。
- `NSUserDefaults` などの具体実装は持たず、抽象インターフェース越しに扱います。
- KMP で最初に読むならここが一番分かりやすいです。

### 4-2. `macosMain`

場所:

- `src/macosMain/kotlin/com/github/gutugutu3030/autodarkmode/shared/`

役割:

- `commonMain` で定義した抽象を、macOS ネイティブ API で実装する層です。

主なファイル:

- `NSUserDefaultsKeyValueStore.kt`
  - `KeyValueStore` の macOS 実装です。
  - `platform.Foundation.NSUserDefaults` を直接使います。

重要ポイント:

- ここは **「共有ロジックと macOS ネイティブ API の接続層」** です。
- `commonMain` の再利用性を壊さずに、永続化だけ macOS 実装に差し替えています。

### 4-3. `macosArm64Main`

場所:

- `src/macosArm64Main/kotlin/com/github/gutugutu3030/autodarkmode/app/`

役割:

- 実アプリの本体です。
- AppKit UI、周囲光センサー、外観切り替え、CLI、LaunchAgent 管理などが入ります。

主なファイル:

- `Main.kt`
  - アプリの entry point。
  - CLI モードなら `CalibrationCli` に分岐し、通常起動なら `NSApplication` とメニューバー UI を立ち上げます。
  - `StatusBarCoordinator` が実質的なアプリのオーケストレータです。

- `StateStore.kt`
  - アプリ内部状態の中心です。
  - センサー読み取り結果、手動 brightness イベント、Auto/Manual/Off の遷移、クールダウン、ヒステリシス、表示メッセージなどをまとめています。

- `PersistedSettings.kt`
  - `SettingsStoreLogic` と `NSUserDefaultsKeyValueStore` を束ね、アプリ側が使いやすい `PersistedSettingsClient` として見せます。
  - 共有 enum `SwitchMode` とアプリ enum `Mode` の変換もここで行っています。

- `NativeAmbientLightReader.kt`
  - 周囲光センサー読み取りの実装です。
  - private framework `BezelServices` を `dlopen` / `dlsym` で動的ロードし、`IOHID` イベントを読んで lux を返します。

- `AppearanceController.kt`
  - macOS の Light / Dark 切り替えを担当します。
  - 実装は `osascript` 経由で `System Events` を操作します。

- `SettingsWindowController.kt`
  - AppKit の設定ウィンドウをコードで構築します。

- `LaunchAtLoginManager.kt`
  - `~/Library/LaunchAgents` に plist を書き、ログイン時起動を管理します。

- `CalibrationCli.kt`
  - `sample` / `watch` / `appearance` サブコマンドを提供する CLI です。

重要ポイント:

- **UI と macOS 固有の挙動はほぼここに集約**されています。
- いまの実行バイナリは `macosArm64` なので、実アプリの中心は `macosArm64Main` にあります。
- `macosX64` ターゲットは build 定義にはありますが、現状は専用ソースセットを持たず、`macosMain` 以下の共通部分を共有する形です。

### 4-4. `nativeInterop`

場所:

- `src/nativeInterop/cinterop/ambientlight.def`
- `src/nativeInterop/cinterop/ambientlight.h`

役割:

- Kotlin/Native の cinterop に C ヘッダを食わせるための設定です。
- private framework / IOKit まわりで必要な型・シンボル宣言を Kotlin 側から見えるようにするための入口です。

重要ポイント:

- KMP の source set というより、**Kotlin/Native のネイティブブリッジ定義置き場**です。
- `build.gradle.kts` で `macosArm64` の main compilation に対して有効化されています。

---

## 5. このリポジトリにおける KMP の source set 階層

`build.gradle.kts` では明示的に `commonMain`, `commonTest`, `macosArm64`, `macosX64` を扱っていますが、KMP では中間 source set が自動で入ります。

`build/kotlinProjectStructureMetadata/kotlin-project-structure-metadata.json` を見ると、現状の main 系は概念上こうなっています。

```text
commonMain
  └─ nativeMain
      └─ appleMain
          └─ macosMain
              ├─ macosArm64Main
              └─ macosX64Main（専用ディレクトリは未使用）
```

ポイント:

- `commonMain`
  - 全ターゲット共通
- `nativeMain`
  - Native ターゲット共通の中間層
- `appleMain`
  - Apple 系ターゲット共通の中間層
- `macosMain`
  - macOS 共通
- `macosArm64Main`
  - arm64 向け最終実装

このリポジトリでは `src/nativeMain/` や `src/appleMain/` は実際には作っていません。**存在しない = 使っていない**というだけで、概念上の依存階層としては存在しています。

つまり、KMP 的には次の理解で十分です。

- 共有ロジックは `commonMain`
- macOS 共通の実装は `macosMain`
- Apple Silicon 実行系は `macosArm64Main`

---

## 6. ネイティブソースとの連携方法

このプロジェクトの「ネイティブ連携」は 1 パターンではありません。主に 4 パターンあります。

### 6-1. Kotlin/Native の platform API をそのまま使う

例:

- `platform.AppKit.*`
- `platform.Foundation.*`
- `platform.IOKit.*`
- `platform.posix.*`

たとえば:

- `Main.kt` は `NSApplication`, `NSMenu`, `NSTimer` を使う
- `SettingsWindowController.kt` は `NSWindow`, `NSButton`, `NSSlider` を使う
- `NSUserDefaultsKeyValueStore.kt` は `NSUserDefaults` を使う
- `LaunchAtLoginManager.kt` は Foundation + POSIX で plist 読み書きをする

これは Java/Kotlin から JNI を叩く感じというより、**Kotlin/Native が提供する Objective-C / C ブリッジを普通の Kotlin import のように使う感覚**に近いです。

### 6-2. cinterop で C ヘッダを認識させる

該当ファイル:

- `src/nativeInterop/cinterop/ambientlight.def`
- `src/nativeInterop/cinterop/ambientlight.h`

`build.gradle.kts` の該当箇所:

- `macosArm64 { compilations.getByName("main").cinterops.create("ambientlight") { ... } }`

これにより、Kotlin/Native コンパイラは `ambientlight.h` に書かれた型やシンボル宣言を理解できるようになります。

このリポジトリでは特に以下のような宣言を置いています。

- `ALCALSCopyALSServiceClient`
- `IOHIDServiceClientCopyEvent`
- `IOHIDEventGetFloatValue`

### 6-3. private framework を動的ロードする

該当ファイル:

- `src/macosArm64Main/kotlin/.../NativeAmbientLightReader.kt`

流れ:

1. `BezelServices` のバイナリパスを指定する
2. `dlopen()` で framework を開く
3. `dlsym()` で必要なシンボルを解決する
4. 関数ポインタとして `reinterpret` して呼ぶ
5. `IOHID` のイベントから lux を読む

つまり、ここでは「ヘッダで宣言を与える」ことと「実体を実行時に動的ロードする」ことを分けています。

この分離により、private framework 依存を `NativeAmbientLightReader.kt` 周辺へ閉じ込めています。

### 6-4. シェル経由で macOS 機能を呼ぶ

該当ファイル:

- `src/macosArm64Main/kotlin/.../AppearanceController.kt`

ここでは `osascript` を `popen()` で実行し、AppleScript で `System Events` の dark mode を読み書きしています。

つまり外観切り替えは AppKit API ではなく、**AppleScript + System Events** です。

---

## 7. 実行時の主要データフロー

アプリ起動から動作までを、読む順番に沿って簡単に書くとこうです。

### 通常起動

1. `Main.kt` の `main()` が呼ばれる
2. CLI 引数なら `CalibrationCli` へ分岐
3. 通常起動なら `NSApplication` を初期化
4. `StatusBarCoordinator.start()` が呼ばれる
5. `NativeAmbientLightReader` / `PersistedSettings` / `StateStore` / `LaunchAtLoginManager` を組み合わせる
6. タイマーで
   - brightness 系イベント
   - ambient light 読み取り
   を定期実行する
7. `StateStore` が状態遷移を行う
8. メニュー UI / 設定ウィンドウへ反映する

### 設定保存

1. UI またはメニューからモード・しきい値変更
2. `StateStore` が `PersistedSettings` を呼ぶ
3. `PersistedSettings` が `SettingsStoreLogic` を呼ぶ
4. `SettingsStoreLogic` が `KeyValueStore` を通じて保存する
5. 実体は `NSUserDefaultsKeyValueStore` が `NSUserDefaults` に書く

### 周囲光での自動切り替え

1. `NativeAmbientLightReader.currentReading()` が lux を返す
2. `StateStore.onEngineTimerTick()` が受け取る
3. `evaluateAutoAppearance()` が
   - dark/light しきい値
   - 連続サンプル数
   - クールダウン
   を評価する
4. 切り替え必要なら `AppearanceController.setAppearance()` を呼ぶ

---

## 8. テスト source set の見方

### `commonTest`

例:

- `SettingsStoreLogicTest.kt`
- `SwitchModeTest.kt`
- `InMemoryKeyValueStore.kt`

役割:

- 共有ロジックの純 Kotlin テスト
- migration / default 値 / clamp の確認

### `macosTest`

例:

- `NSUserDefaultsKeyValueStoreTest.kt`

役割:

- `NSUserDefaults` を使う macOS 共通実装のテスト

### `macosArm64Test`

例:

- `PersistedSettingsIntegrationTest.kt`
- `StateStoreTest.kt`
- `AppearanceControllerTest.kt`
- `LaunchAtLoginManagerTest.kt`
- `CalibrationCliTest.kt`

役割:

- 実アプリ層のロジックテスト
- 設定統合、外観切り替え、LaunchAgent、CLI などの検証

KMP 的には、**テストも source set ごとに責務分担されている**と考えると理解しやすいです。

---

## 9. 最初に読むならこの順番がおすすめ

KMP 未経験者向けには、次の順番が一番入りやすいです。

1. `README.md`
   - アプリの目的と操作対象を把握する
2. `build.gradle.kts`
   - ターゲット、entry point、cinterop を把握する
3. `src/commonMain/.../SettingsStoreLogic.kt`
   - 共有ロジックの核を理解する
4. `src/macosMain/.../NSUserDefaultsKeyValueStore.kt`
   - 共有抽象が macOS 実装へ落ちる場所を見る
5. `src/macosArm64Main/.../PersistedSettings.kt`
   - 共通ロジックとアプリ層のつなぎ方を見る
6. `src/macosArm64Main/.../StateStore.kt`
   - アプリ内部の状態遷移を理解する
7. `src/macosArm64Main/.../Main.kt`
   - 起動フローと UI 更新の全体像を見る
8. `src/macosArm64Main/.../NativeAmbientLightReader.kt`
   - private framework 連携を確認する
9. `src/macosArm64Main/.../SettingsWindowController.kt`
   - AppKit UI の作り方を確認する

---

## 10. KMP 未経験者がハマりやすい点

### `macosMain` と `macosArm64Main` の違い

- `macosMain` は macOS 共通の層
- `macosArm64Main` は arm64 実行ターゲット固有の最終層

「AppKit を使っているから全部 `macosMain` では？」と思いがちですが、このリポジトリでは**実行バイナリの本体を arm64 側に寄せている**ため、アプリ本体は `macosArm64Main` にあります。

### `nativeInterop` は普通の Kotlin ソースではない

- ここは `.kt` を置く場所ではなく、C ヘッダと `.def` を置く場所です。
- 役割は「Kotlin から見えるようにする」ことです。

### `build/` の source set 情報に `appleMain` や `nativeMain` が見える

- これは KMP が中間 source set を自動生成しているためです。
- `src/appleMain` が無くても異常ではありません。

### 共有化されていない = 悪い設計、ではない

このリポジトリでは、private framework / AppKit / LaunchAgent / `osascript` など、macOS 固有要素が強いです。したがって、**共有すべきものだけ `commonMain` に置き、ネイティブ依存を無理に共通化しない**方針になっています。

これは KMP ではかなり自然な分け方です。

---

## 11. 一言でまとめると

このプロジェクトは、

- `commonMain` に「設定の共有ロジック」
- `macosMain` に「macOS 共通の保存実装」
- `macosArm64Main` に「アプリ本体とネイティブ機能」
- `nativeInterop` に「C / private API との橋渡し」

を置いた Kotlin/Native ベースの macOS メニューバーアプリです。

KMP 未経験でも、まずは **「共通ロジック」と「macOS 実装」を分けているだけ** と捉えると読みやすくなります。そのうえで、必要になったら source set の継承階層を理解するとスムーズです。
