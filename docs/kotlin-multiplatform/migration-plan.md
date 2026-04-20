# Kotlin Multiplatform 移行プラン

本ドキュメントは Issue「kotlin multiplatform への実装変更」**任意要件 2** に対応する移行プランである。
判断の前提は同ディレクトリの [`feasibility.md`](./feasibility.md) を参照。

> **前提**:
> 全面移行は ROI が低いため、本プランは **「ロジック層のみを KMP `commonMain` + `macosMain` に切り出し、UI/センサー/配布は Swift 側に残す」ハイブリッド構成** をターゲットフェーズの最大到達点とする。
> 全面移行（UI まで含む）に進むかは、フェーズ 4 完了時点の評価で別途判断する。

## UI 再評価の前段ゲート

UI の KMP 化を再評価する場合、**設定画面より先に `NSStatusItem` ベースの最小メニューバー常駐プロトタイプ** を成立させる。
理由は、現行 UX の中核が `NSStatusItem` / `NSMenu` / accessory app としての常駐動作にあり、ここが Kotlin/Native 側で安定しない限り、SwiftUI 設定ウィンドウの移植可否を検討しても判断材料として弱いためである。

- プロトタイプ実装: [`../../prototypes/kmp-menubar-poc/`](../../prototypes/kmp-menubar-poc/README.md)
- 評価メモ: [`./nsstatusitem-prototype.md`](./nsstatusitem-prototype.md)

このゲートでは以下だけを確認する。

- Kotlin/Native で macOS の native executable を生成できること
- Kotlin から `NSStatusItem` と `NSMenu` を所有し、常駐できること
- 最低限のメニュー操作と終了処理が動くこと

これが不安定または保守困難なら、UI の全面 KMP 化は早期に撤退する。

現時点では、このゲートの第 1 段階に加えて、状態集約・mode 切り替え・threshold 表示切り替え・icon/tooltip 更新までを Kotlin 側プロトタイプで確認済みである。
ただし、RunLoop mode を意識した更新制御や、本番イベント源との接続は未検証である。

また、BrightnessKeyMonitor 相当と AutoSwitchEngine 相当の入力を別イベント源として流し込み、バースト時に複数 mutation を 1 回の flush に畳めることも PoC 上では確認した。
したがって、**移行に着手する前提条件としての UI 側の最初の技術ゲートは、限定的には通過した** と扱ってよい。

## UI 移行の準備フェーズ

UI を含む KMP 移行を始める前に、まず以下の準備を整える。

1. production Swift 実装の責務境界を固定する。
  - `StatusBarController` が UI 所有、`AutoSwitchEngine` が判断集約、`BrightnessKeyMonitor` がイベント取得、という境界を崩さず棚卸しする。
2. Kotlin 側へ先に移す候補を限定する。
  - 最初の対象は menu presentation state の組み立てに留め、AppKit 実体や権限 API は直ちには移さない。
3. Swift と Kotlin の接続面を定義する。
  - mode, lux, appearance, sensor availability, action description を渡す最小 DTO を定義し、片方向同期から始める。
4. 検証順序を固定する。
  - `kmp/` サブプロジェクト導入
  - presentation state 組み立てロジックの shared 化
  - Swift から shared state を読んで既存 AppKit UI へ反映
  - その後に Kotlin 側 UI ownership を再評価
5. 撤退条件を先に明文化する。
  - RunLoop mode 差分で menu 更新が不安定
  - 権限導線の分離で UX が崩れる
  - Swift/Kotlin 境界のデバッグコストが過大

この準備フェーズでは、いきなり `NSStatusItem` 自体を production で Kotlin 所有に切り替えるのではなく、**まず state assembly の shared 化から着手する**。

## 同等 UX の受け入れ条件

UI を Kotlin 側へ寄せるかを再評価する際は、少なくとも次を満たしたときだけ「現行と同等 UX を維持できる可能性がある」と扱う。

- メニューバー表示中を含め、複数の状態変更が 1 回の presentation 更新へ安定して集約されること
- 設定画面または永続設定の変更が、アプリ再起動なしでメニューバー表示へ反映されること
- Off / Auto / Manual の mode と threshold 表示が、設定画面とメニューバーの両方で矛盾しないこと
- sensor availability, last action, last error, permission support message が stale にならず反映されること
- Accessibility 権限導線と launch-at-login の状態が、既存 Swift 実装と同等に扱えること
- 既存 UserDefaults キー互換を維持したまま rollback できること

逆に、次のどれかが満たせないなら、UI ownership の Kotlin 化は止め、presentation state の shared 化までに留める。

- open menu 中の RunLoop mode 差分で更新が崩れる
- 設定変更の反映が通知依存で不安定になる
- 権限導線または起動設定が Swift 側に残り、境界がかえって複雑化する
- 回帰確認が目視依存のまま増え、検証コストが見合わない

現状の prototype は、この受け入れ条件のうち `NSUserDefaultsDidChangeNotification` 経由の設定反映までを新たに検証対象に含める。

## 0. ゴールと非ゴール

### ゴール
- Issue 必須要件 1 で確認した「現状用意しているテストと同じ要件のテスト」を `kotlin.test` で再現し、CI で実行できる状態にする。
- 純粋ロジック (`SwitchMode`, `SettingsStore` のキー定義 / モード遷移 / 永続化契約) を Kotlin に移植し、Swift 側がそれを `.framework` 経由で利用できるようにする。
- 既存ユーザに対する **挙動上の破壊的変更を発生させない**。
  （内部実装が Kotlin に置き換わっても、メニュー / 設定画面 / 環境光挙動は同一。）

### 非ゴール
- SwiftUI 設定ウィンドウの Compose Multiplatform 化（フェーズ外、要別途検討）。
- `BezelServices` / `AppleLMUController` 呼び出しの Kotlin 化（PrivateFramework 依存のため Obj-C シムを維持）。
- iOS / Android 等への横展開（本リポジトリのスコープ外）。

## 1. 全体フェーズ

| フェーズ | 目的 | 主な成果物 | コミット粒度 |
|----------|------|-------------|----------------|
| **F1: 基盤導入** | KMP プロジェクトを副ディレクトリで立ち上げ、Swift 側ビルドに影響を与えずに同居させる | `kmp/` ディレクトリ + `build.gradle.kts` + Kotlin/Native macosArm64 ターゲット | 1 コミット |
| **F2: ロジック移植** | `SwitchMode` enum と `SettingsStore` の永続化契約を `commonMain` に移植 | `kmp/src/commonMain/kotlin/.../SwitchMode.kt`, `SettingsStoreLogic.kt` | 1 コミット |
| **F3: テスト同等化** | 既存 Swift テストと **同じ要件** のテストを `commonTest` / `macosTest` で記述し、`gradle :kmp:macosArm64Test` で通す | `kmp/src/commonTest/kotlin/.../*Tests.kt` | 1 コミット |
| **F4: Swift 連携** | KMP を `.framework` として出力し、`Package.swift` の `binaryTarget` から取り込む。`SettingsStore` Swift 実装を Kotlin 実装の薄いラッパに置き換え、既存 Swift テストも引き続き green を保つ | `Package.swift` 更新, `SettingsStore.swift` 内部実装差し替え | 1 コミット |
| **F5: CI 統合** | `.github/workflows/` に Gradle 用ジョブを追加し、Kotlin/Native ツールチェインを CI で確実にインストールさせる | ワークフロー更新, `Scripts/validate.sh` から KMP テスト呼び出し | 1 コミット |
| **F6 (任意): 評価と判断** | 実コスト計測、ビルド時間・CI 時間・バイナリサイズの差分を `feasibility.md` 末尾に追記し、UI 層も移行するか判断 | 追記のみ | 1 コミット |

各フェーズは **必ず単独で push** し、途中で停止しても前段の成果が残る構成にする（Issue 要求準拠）。

## 2. ディレクトリ配置案

```
.
├── Package.swift                       # 既存
├── Sources/                            # 既存（Swift / Obj-C）
├── Tests/                              # 既存（Swift Testing）
├── kmp/                                # 新規。KMP サブプロジェクト一式
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/com/github/gutugutu3030/autodarkmode/
│       │   ├── SwitchMode.kt
│       │   └── SettingsStoreLogic.kt   # KeyValueStore 抽象を依存注入
│       ├── commonTest/kotlin/.../
│       │   ├── SwitchModeTests.kt
│       │   └── SettingsStoreLogicTests.kt
│       ├── macosMain/kotlin/.../
│       │   └── NSUserDefaultsKeyValueStore.kt  # platform.Foundation.NSUserDefaults アダプタ
│       └── macosTest/kotlin/.../
│           └── NSUserDefaultsKeyValueStoreTests.kt
└── docs/kotlin-multiplatform/          # 本ドキュメント群
```

`kmp/` を Swift Package と独立させることで、F1〜F3 までは Swift 側のビルド・テスト・CI に一切影響を与えずに導入できる。

## 3. 段階別の詳細手順

### F1: 基盤導入
1. リポジトリ直下に `kmp/` を作成。`gradle init --type kotlin-library` 相当の最小構成を手書き。
2. `kotlin("multiplatform")` プラグインを使い、`macosArm64` と `macosX64` ターゲットを宣言する。macOS 13+ はリポジトリ方針として維持し、必要なら後続フェーズで deployment target の付与方法を再評価する。
3. `gradle wrapper` をコミットし、開発者・CI で同一バージョンを使用。
4. `.gitignore` に `kmp/.gradle/`, `kmp/build/` を追加。
5. ローカルで `cd kmp && ./gradlew tasks` が成功することを確認。

**完了条件**: `cd kmp && ./gradlew build` がノーソースで通る（成果物は空）。Swift 側のビルドに影響なし。

### F2: ロジック移植
1. `SwitchMode.kt`:
   ```kotlin
   enum class SwitchMode(val rawValue: String) {
       Off("off"), Auto("auto"), Manual("manual");
       companion object {
           fun fromRawValue(raw: String?): SwitchMode? = entries.find { it.rawValue == raw }
       }
   }
   ```
   既存 Swift `SwitchMode` の `rawValue` を 1 対 1 で踏襲し、UserDefaults 上の文字列互換性を担保する。
2. `KeyValueStore` インタフェースを `commonMain` に定義し、`getString/setString`, `getDouble/setDouble`, `getBool/setBool` 等、`SettingsStore.swift` が実際に使うメソッドのみを最小に切り出す。
3. `SettingsStoreLogic` を `commonMain` に移植。`@MainActor` 相当のスレッド要件は呼び出し側責務とし、Kotlin 側はスレッドフリーに書く（`MutableStateFlow` 等）。

**完了条件**: `cd kmp && ./gradlew compileKotlinMacosArm64` が通る。

### F3: テスト同等化
- 既存 `SettingsStoreTests` の各 `@Test` ケースに対し、**同じ振る舞いを検証する `kotlin.test`** ケースを 1 対 1 で書く。
  - `defaultSwitchMode is auto` → `assertEquals(SwitchMode.Auto, store.switchMode)`
  - `switchMode persists to UserDefaults` → InMemory な `KeyValueStore` 実装でキー `"switchMode"` の値を確認
- `commonTest` で書いた純粋ロジックテストに加え、`macosTest` で `NSUserDefaultsKeyValueStore` 実装の往復テストを 1 本追加（`SettingsStoreTests` の `makeIsolatedDefaults()` と同等の suite name 戦略）。
- `SwitchModeTests` 側も同様に再記述。

**完了条件**: `cd kmp && ./gradlew macosArm64Test` が green。Swift 側 `swift test` も green を維持。

### F4: Swift 連携
1. `kmp/build.gradle.kts` に XCFramework 出力タスクを追加（`XCFramework().add(...)`）。
2. `Package.swift` に `.binaryTarget(name: "AutoDarkModeKMP", path: "kmp/build/XCFrameworks/release/AutoDarkModeKMP.xcframework")` を追加し、`autoDarkMode` 実行ターゲットの `dependencies` に加える。
3. Swift 側 `SettingsStore` を、内部状態を Kotlin 製 `SettingsStoreLogic` に委譲する **薄いラッパ** にリファクタ。`@Published` プロパティの公開 API は変えない（既存テスト・既存 UI を保護）。
4. `Scripts/build-app.sh` の手前に `cd kmp && ./gradlew assembleAutoDarkModeKMPXCFramework` を呼び出すフックを追加。

**完了条件**:
- `./Scripts/validate.sh` 全体が green。
- 既存 Swift テストがすべて green（書き換えていない）。
- アプリ起動時の挙動・設定の互換性（既存ユーザの UserDefaults キー / 値）を維持。

### F5: CI 統合
1. `.github/workflows/` に Gradle セットアップ（`actions/setup-java@v4` + `gradle/actions/setup-gradle`）と Kotlin/Native キャッシュ（`~/.konan`）を追加。
2. PR ジョブで `cd kmp && ./gradlew check` を必須化。
3. Release ジョブ（タグ起動）でも XCFramework を先にビルドしてから既存の `.app` パッケージングへ進む。
4. `.github/README.md` に CI 構成の変更点を追記。

**完了条件**: PR で 2 系統（Swift 側 / KMP 側）のチェックが両方走る。

### F6 (任意): 評価
- ローカル及び CI のビルド時間（cold/warm）を計測し `feasibility.md` 末尾に追記。
- KMP 化したことで得られた利点（共有候補ロジック、テストの言語選択肢、再利用先候補）を列挙。
- ROI を再評価し、UI 層の Compose Multiplatform 移行に進むか / ハイブリッド維持か / KMP 撤退かを判断。

## 4. 既知のリスクと回避策

| リスク | 影響 | 回避策 |
|--------|------|--------|
| Kotlin/Native の cold build が長く CI が遅くなる | 開発者体験悪化 | `~/.konan` キャッシュ + Gradle build cache を CI に必ず入れる。`commonTest` のみを必須化、`macosTest` はオプショナル化を検討。 |
| Swift から見た KMP `.framework` のシンボル名が衝突する | ビルド失敗 | Kotlin 側パッケージ名にプレフィクス（`AutoDarkModeKMP`）を付けて `framework { baseName = "AutoDarkModeKMP" }` で名前空間を分離。 |
| 既存 UserDefaults キー (`switchMode`, 閾値等) との後方互換破壊 | ユーザ設定喪失 | F2 でキー名・値（`"off"`/`"auto"`/`"manual"`）を Swift と一致させ、F4 移行時に手動マイグレーションテストを追加する。 |
| Xcode / Swift toolchain と Kotlin/Native の Xcode 要件の食い違い | ビルドが通らない環境が出る | サポート Xcode バージョンを README に明記。`./Scripts/validate.sh` で `xcrun --find swift` の検出ロジックは現状維持。 |
| `.framework` を含めることで `.app` バンドルサイズが増大 | 配布インパクト | 初回計測（F4 完了時）の差分を `feasibility.md` に追記し、許容範囲か判断。許容外なら撤退。 |
| Swift Testing と kotlin.test で実行レポートが分散 | レビュー時の見通し悪化 | CI ジョブ名を `swift-test` / `kmp-test` に分けて status check 名を一意化。 |

## 5. ロールバック方針

各フェーズが独立コミットになっているため、問題が発生した時点のコミットを `git revert` するだけで前段に戻れる。
特に F4（Swift 側依存先を切り替えるフェーズ）は、revert しても `kmp/` ディレクトリが残るのみで Swift 側の挙動は完全に元に戻る。

## 6. 本 Issue の進行スコープ

本 PR では **任意要件 3（実際の移行着手）には踏み込まない**。
理由は [`feasibility.md`](./feasibility.md) §4 のとおり、

- マルチプラットフォーム展開計画が無い現時点では ROI が見合わない
- F1〜F5 を実施しても **ユーザ体験は変わらず**、内部のツールチェインだけが複雑化する
- PrivateFramework 依存は KMP 化しても消えない

ためである。
本ドキュメントは「いざ着手するときの最短経路」を残す目的で整備したものであり、
着手判断はメンテナの方針（他 OS への展開意思 / Compose Multiplatform 採用意思）に依存する。
