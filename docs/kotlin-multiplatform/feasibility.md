# Kotlin Multiplatform 移行可能性調査

本ドキュメントは Issue「kotlin multiplatform への実装変更」必須要件 1 に対応する調査メモである。
「機能は追加せず、現状用意しているテストと同じ要件のテストを Kotlin Multiplatform (以下 KMP) 上で通せるか」を判断基準とする。

## 1. 現状の autoDarkMode が依存している Apple 専用要素

| 区分 | 利用箇所 | 依存している API / 仕組み | 公開 / 非公開 |
|------|----------|----------------------------|----------------|
| 環境光センサー（主経路） | `Sources/ALSBridge/ALSBridge.m` | `BezelServices` (`BSDoGraphicWithMeterAndTimeout` 周辺), `IOHIDServiceClient` | **非公開** PrivateFrameworks |
| 環境光センサー（フォールバック） | `Sources/ALSBridge/ALSBridge.m` | `IOServiceMatching("AppleLMUController")` + `IOConnectCallMethod` | 半公開（IOKit ユーザクライアント、シンボルは非公開動作） |
| 外観切り替え | `Sources/autoDarkMode/Appearance/AppearanceController.swift` | `osascript` 経由で `System Events` の AppleScript を実行 | 公開（AppleScript） |
| メニューバー UI | `Sources/autoDarkMode/UI/StatusBarController.swift` | `NSStatusItem`, `NSMenu`, `NSImage`（AppKit） | 公開 |
| 設定ウィンドウ | `Sources/autoDarkMode/UI/SettingsView.swift`, `SettingsWindowController.swift` | SwiftUI (`@State`, `Form`, `Picker` …) + `NSWindow` | 公開（ただし **SwiftUI は Swift 専用**） |
| 輝度キー監視 | `Sources/autoDarkMode/Application/BrightnessKeyMonitor.swift` | `NSEvent.addGlobalMonitorForEvents`、`AXIsProcessTrustedWithOptions` | 公開 |
| ログイン項目 | `Sources/autoDarkMode/Application/LaunchAtLoginManager.swift` | `SMAppService`（ServiceManagement） | 公開 |
| 設定保存 | `Sources/autoDarkMode/Application/SettingsStore.swift` | `UserDefaults` + `@MainActor` の `ObservableObject` | 公開（が、`ObservableObject` は SwiftUI 連携前提） |
| テスト基盤 | `Tests/autoDarkModeTests/*.swift` | Swift Testing (`@Suite`, `@Test`, `#expect`) + `@testable import` | **Swift 専用** |

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
| **BezelServices（PrivateFramework）** | Kotlin/Native の cinterop は公開ヘッダ前提。プライベートシンボルは結局 `dlopen` / `dlsym` を Obj-C/C で書く必要があり、現状の `ALSBridge.m` 相当の Obj-C シムが残る。Swift から呼ぶか Kotlin から呼ぶかの違いだけで、コード量は減らない。 |
| **`AppleLMUController` の `IOConnectCallMethod` 呼び出し** | 引数のスカラー数・出力サイズ等が機種固有のヒューリスティックで決まっている。KMP の cinterop でも書けるが、現状の Obj-C コードを 1 対 1 で書き直すだけで価値は出ない。 |
| **SwiftUI 製の設定ウィンドウ** | SwiftUI は Swift コンパイラ・マクロに密結合しており、Kotlin から再利用不可。代替は AppKit を cinterop で叩く（記述量大）か、Compose Multiplatform for macOS（実験的、`NSStatusItem` 連携は Compose の責務外）。いずれにせよ **既存 UI は全面再実装** になる。 |
| **`@MainActor` / Swift Concurrency 前提のクラス** | `SettingsStore` 等が `@MainActor` で組まれているため、Kotlin 側では `Dispatchers.Main` (`NSRunLoop` ベース) に置き換える設計が必要。挙動は再現可能だが、`ObservableObject` の `@Published` 相当は KMP 単独では存在せず、`MutableStateFlow` などへ書き換える必要がある。 |
| **Swift Testing (`@Test`, `#expect`) との互換性** | KMP 側のテストは `kotlin.test` になるため、テストコードは **同じ要件・同じケース名で再記述** することになる。「同じテストをそのまま通す」のは不可。**「同じ要件のテストを通す」までが現実解**。 |
| **CI / 配布（`build-app.sh`, GitHub Actions）** | 現状の Swift Package + `xcodebuild` 流の `.app` パッケージングを Kotlin/Native の出力（`.kexe` / `.framework`）から再構築する必要がある。`Info.plist`, アイコン, コードサイン, ServiceManagement 用 `LaunchDaemons` 配置などを手動で組み立てる Gradle タスクが必要。 |

## 4. 結論：移行可能性

| 観点 | 判定 |
|------|------|
| **純粋ロジック層（`SwitchMode`, `SettingsStore` のキー定義・モード遷移）を KMP に移植し、現行と同じ要件のテストを kotlin.test で通すこと** | ✅ 可能 |
| **環境光センサー読み出し（BezelServices / AppleLMUController）を KMP “だけ” で完結させること** | ❌ 不可。Obj-C/C の薄いシム（現 `ALSBridge` 相当）が残る。 |
| **メニューバー UI と SwiftUI 設定ウィンドウを KMP で再実装し、現行と同等の UX を維持すること** | ⚠️ 技術的には可能だが、SwiftUI を捨てて AppKit cinterop か Compose Multiplatform への全面書き換えになる。実装コストは新規アプリを作るのに近い。 |
| **「機能は追加せず、現状用意しているテストと同じ要件のテストを KMP で通せる」状態に到達できるか** | ✅ 可能（純粋ロジック層に限れば）。ただし UI / センサー層は Apple ネイティブのまま残すか、別途 KMP で書き直す前提のいずれか。 |

### 総合判定

> **「ロジック層 (`commonMain` + `macosMain`) を KMP に移植し、同じ要件の単体テストを通す」までは現実的に可能。**
> **一方、アプリ全体（センサー / メニューバー / 設定 UI / 配布）を KMP に置き換えるのは、現時点で純粋にコスト超過の判断となる。**

理由の要点:

1. **マルチプラットフォーム価値がゼロに近い**
   本アプリは macOS 専用機能（環境光センサー + ダーク/ライト切替）に依存しており、他プラットフォームへ展開する計画も Issue 内では示されていない。KMP の最大の利点である「複数 OS でのコード共有」が活かせない。
2. **私的 API (BezelServices) は Obj-C シムが必須**
   どの言語から呼ぶにせよ Obj-C ランタイムへの薄い橋渡しは消えない。Swift → Obj-C と Kotlin/Native → Obj-C のどちらでも書ける以上、Swift 側を残すのが既存資産を活かせる。
3. **テスト互換性は「要件互換」までが上限**
   Issue の必須要件「現状用意しているテストと同じ要件のテストを通す」は、Swift Testing から `kotlin.test` への書き換えで満たせる。ただし「同じテストコードがそのまま動く」わけではない点に注意。
4. **配布ワークフロー (`Scripts/build-app.sh`, `.github/workflows/`) の再構築コストが大きい**
   `.app` バンドル生成・コードサイン・タグドリブン Release 自動化を Gradle 側で再構築する必要があり、調査価値に対し作業量が見合わない。

## 5. 「最小限で要件を満たす」場合の最短コース（参考）

純粋ロジック層だけを KMP に移し、UI / センサー / 配布は Swift のままに残す “ハイブリッド最小構成” であれば、
Issue 必須要件 1 の判断基準（同要件のテストを通す）を満たせる。

- `commonMain` に `SwitchMode`（enum）と `SettingsStoreLogic`（UserDefaults 抽象を受け取る pure logic）を置く。
- `commonTest` に現行 `SwitchModeTests`, `SettingsStoreTests` の **要件と同じ** ケースを `kotlin.test` で再記述する。
- `macosMain` で `NSUserDefaults` 実装をバインドし、Swift から `.framework` として利用する想定にする。

ただし上記でも

- Gradle / Kotlin/Native ツールチェインの追加（CI 影響）
- `.framework` 生成と Swift 側 import 追加
- 既存 `SettingsStore` を二重定義しない設計判断

が必要になり、**現アプリの単純性に対して相応のオーバーヘッド**となる。
最終的な採否はメンテナの判断に委ねる。

## 6. 推奨

- **必須要件 1 の判定**: 「ロジック層に限れば移行可能、アプリ全体としては非推奨」とする。
- **任意要件 2** に進む場合は、本ドキュメントの 5 章を出発点として `migration-plan.md` で詳細化する。
- **任意要件 3**（実際の移行着手）は、KMP 化による具体的なメリット（他 OS 展開、ロジックの再利用先）が明確になってから着手することを推奨する。
