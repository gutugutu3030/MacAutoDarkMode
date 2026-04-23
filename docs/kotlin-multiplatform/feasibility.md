# Kotlin Multiplatform 移行可能性調査

本ドキュメントは Issue「kotlin multiplatform への実装変更」必須要件 1 に対応する調査メモである。
「機能は追加せず、現状用意しているテストと同じ要件のテストを Kotlin Multiplatform (以下 KMP) 上で通せるか」を判断基準とする。

> **注記**: 本ドキュメントは移行検討時の調査メモを現行コードベースに合わせて更新したものである。現在のリポジトリはすでに Kotlin/Native の menu bar app 実装へ移行しており、`Sources/ALSBridge` や Swift 製 UI を現行依存として前提にしない。

## 1. 現状の autoDarkMode が依存している Apple 専用要素

| 区分 | 利用箇所 | 依存している API / 仕組み | 公開 / 非公開 |
|------|----------|----------------------------|----------------|
| 環境光センサー | 現行 Kotlin runtime (`src/macosArm64Main`) | `NativeAmbientLightReader` による `BezelServices` の `dlopen` / `dlsym` と `IOHIDServiceClient` | **非公開** PrivateFrameworks |
| 外観切り替え | 現行 Kotlin runtime (`src/macosArm64Main`) | `osascript` 経由で `System Events` の AppleScript を実行 | 公開（AppleScript） |
| メニューバー UI | 現行 Kotlin runtime (`src/macosArm64Main`) | `NSStatusItem`, `NSMenu`, `NSImage`（AppKit） | 公開 |
| 設定ウィンドウ | 現行 Kotlin runtime (`src/macosArm64Main`) | AppKit (`NSWindow`, `NSTextField`, `NSButton`, `NSSlider`) | 公開 |
| 輝度キー監視 | 現行 Kotlin runtime (`src/macosArm64Main`) | `NSEvent` 相当の監視状態管理と Accessibility permission 導線 | 公開 |
| ログイン項目 | 現行 Kotlin runtime (`src/macosArm64Main`) | per-user LaunchAgent 管理 | 公開 |
| 設定保存 | shared logic (`src/commonMain`, `src/macosMain`) | `NSUserDefaults` + Kotlin state | 公開 |
| テスト基盤 | `src/commonTest`, `src/macosTest`, `src/macosArm64Test` | `kotlin.test` | Kotlin/Native 前提 |

## 2. KMP でカバーできる範囲

Kotlin/Native は `macosArm64` / `macosX64` ターゲットを公式サポートしており、
以下は **技術的には可能** である。

- **公開 IOKit / Foundation / AppKit の cinterop**
  Kotlin/Native の `cinterop` で `<IOKit/IOKitLib.h>`, `<AppKit/AppKit.h>` の def ファイルを書けば、
  `IOServiceMatching` や `NSStatusBar` を Kotlin から呼べる。
- **AppleScript 実行**
  `NSAppleScript` 相当を AppKit cinterop 経由で叩く、または `Process` 相当（`posix_spawn`）で `osascript` を起動することは可能。
- **UserDefaults**
  `platform.Foundation.NSUserDefaults` 経由で読み書き可能。`commonMain` には置けないため `macosMain` 限定。
- **テスト**
  `kotlin.test` + `org.jetbrains.kotlin.test.junit` で Swift Testing と同等の単体テストは記述できる。
  現行の `SettingsStoreTests`, `SwitchModeTests` は本質的に純粋ロジック（モード遷移、UserDefaults キーへの永続化）であり、
  KMP 上で **同じ要件** を満たすテストを再記述すること自体に技術的障壁は無い。

## 3. KMP では実質的に困難な範囲

| 項目 | 困難の理由 |
|------|-------------|
| **BezelServices（PrivateFramework）** | 現行実装でも公開ヘッダには依存せず、Kotlin/Native から `dlopen` / `dlsym` で動的解決している。PrivateFramework 依存そのものは残るため、移植課題は「Objective-C シムの要否」ではなく「非公開 API 依存を維持したまま Kotlin/Native 側でどこまで閉じ込めるか」に変わっている。 |
| **SwiftUI 製の設定ウィンドウ** | SwiftUI は Swift コンパイラ・マクロに密結合しており、Kotlin から再利用不可。代替は AppKit を cinterop で叩く（記述量大）か、Compose Multiplatform for macOS（実験的、`NSStatusItem` 連携は Compose の責務外）。いずれにせよ **既存 UI は全面再実装** になる。 |
| **`@MainActor` / Swift Concurrency 前提のクラス** | `SettingsStore` 等が `@MainActor` で組まれているため、Kotlin 側では `Dispatchers.Main` (`NSRunLoop` ベース) に置き換える設計が必要。挙動は再現可能だが、`ObservableObject` の `@Published` 相当は KMP 単独では存在せず、`MutableStateFlow` などへ書き換える必要がある。 |
| **Swift Testing (`@Test`, `#expect`) との互換性** | KMP 側のテストは `kotlin.test` になるため、テストコードは **同じ要件・同じケース名で再記述** することになる。「同じテストをそのまま通す」のは不可。**「同じ要件のテストを通す」までが現実解**。 |
| **CI / 配布（`build-app.sh`, GitHub Actions）** | 現状の Swift Package + `xcodebuild` 流の `.app` パッケージングを Kotlin/Native の出力（`.kexe` / `.framework`）から再構築する必要がある。`Info.plist`, アイコン, コードサイン, ServiceManagement 用 `LaunchDaemons` 配置などを手動で組み立てる Gradle タスクが必要。 |

## 4. 結論：移行可能性

| 観点 | 判定 |
|------|------|
| **純粋ロジック層（`SwitchMode`, `SettingsStore` のキー定義・モード遷移）を KMP に移植し、現行と同じ要件のテストを kotlin.test で通すこと** | ✅ 可能 |
| **環境光センサー読み出し（BezelServices + `IOHIDServiceClient`）を現行 Kotlin 実装のまま維持すること** | ✅ 可能。現状コードがすでに `dlopen` / `dlsym` による Kotlin/Native 実装へ移行済み。 |
| **メニューバー UI と SwiftUI 設定ウィンドウを KMP で再実装し、現行と同等の UX を維持すること** | ⚠️ 技術的には可能だが、SwiftUI を捨てて AppKit cinterop か Compose Multiplatform への全面書き換えになる。実装コストは新規アプリを作るのに近い。 |
| **「機能は追加せず、現状用意しているテストと同じ要件のテストを KMP で通せる」状態に到達できるか** | ✅ 可能（純粋ロジック層に限れば）。ただし UI / センサー層は Apple ネイティブのまま残すか、別途 KMP で書き直す前提のいずれか。 |

### 総合判定

> **「ロジック層 (`commonMain` + `macosMain`) を KMP に移植し、同じ要件の単体テストを通す」までは現実的に可能。**
> **一方、アプリ全体（センサー / メニューバー / 設定 UI / 配布）を KMP に置き換えるのは、現時点で純粋にコスト超過の判断となる。**

理由の要点:

1. **マルチプラットフォーム価値がゼロに近い**
   本アプリは macOS 専用機能（環境光センサー + ダーク/ライト切替）に依存しており、他プラットフォームへ展開する計画も Issue 内では示されていない。KMP の最大の利点である「複数 OS でのコード共有」が活かせない。
2. **私的 API (BezelServices) 依存は今も残る**
   ただし現状コードでは `NativeAmbientLightReader` が `dlopen` / `dlsym` で直接扱っており、`ALSBridge` のような別ターゲット前提ではない。移行判断では「Obj-C シムが必要か」ではなく「PrivateFramework 依存を許容するか」が論点になる。
3. **テスト互換性は「要件互換」までが上限**
   Issue の必須要件「現状用意しているテストと同じ要件のテストを通す」は、Swift Testing から `kotlin.test` への書き換えで満たせる。ただし「同じテストコードがそのまま動く」わけではない点に注意。
4. **配布ワークフロー (`Scripts/build-app.sh`, `.github/workflows/`) の再構築コストが大きい**
   `.app` バンドル生成・コードサイン・タグドリブン Release 自動化を Gradle 側で再構築する必要があり、調査価値に対し作業量が見合わない。

## 5. 「最小限で要件を満たす」場合の最短コース（参考）

現行リポジトリはすでに Kotlin/Native ベースの app runtime を採用しているため、
この章は「移行検討の最小単位」を読み替える参考情報である。
現在の観点では、shared logic を `commonMain` / `macosMain` に閉じ込めつつ、AppKit UI・周囲光読み取り・配布スクリプトを macOS 向け Kotlin 実装として維持する構成が、
Issue 必須要件 1 の判断基準（同要件のテストを通す）に対応する最短コースである。

- `commonMain` に `SwitchMode`（enum）と `SettingsStoreLogic`（UserDefaults 抽象を受け取る pure logic）を置く。
- `commonTest` に現行 `SwitchModeTests`, `SettingsStoreTests` の **要件と同じ** ケースを `kotlin.test` で再記述する。
- `macosMain` で `NSUserDefaults` 実装をバインドし、`macosArm64Main` の app runtime から直接利用する。

ただし上記でも

- Gradle / Kotlin/Native ツールチェインの追加（CI 影響）
- arm64 / x64 向け Kotlin/Native ターゲットの保守
- 既存 `SettingsStore` を二重定義しない設計判断

が必要になり、**現アプリの単純性に対して相応のオーバーヘッド**となる。
最終的な採否はメンテナの判断に委ねる。

## 6. 推奨

- **必須要件 1 の判定**: 「ロジック層に限れば移行可能、アプリ全体としては非推奨」とする。
- **任意要件 2** に進む場合は、本ドキュメントの 5 章を出発点として `migration-plan.md` で詳細化する。
- **任意要件 3**（実際の移行着手）は、KMP 化による具体的なメリット（他 OS 展開、ロジックの再利用先）が明確になってから着手することを推奨する。
