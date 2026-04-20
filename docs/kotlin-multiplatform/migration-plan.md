# Kotlin Multiplatform 移行プラン

本ドキュメントは Issue「kotlin multiplatform への実装変更」**任意要件 2** に対応する移行プランである。
判断の前提は同ディレクトリの [`feasibility.md`](./feasibility.md) を参照。

> **前提**:
> 全面移行は ROI が低いため、本プランは **「ロジック層のみを KMP `commonMain` + `macosMain` に切り出し、UI/センサー/配布は Swift 側に残す」ハイブリッド構成** をターゲットフェーズの最大到達点とする。
> 全面移行（UI まで含む）に進むかは、フェーズ 4 完了時点の評価で別途判断する。

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
2. `kotlin("multiplatform")` プラグインを使い、`macosArm64` と `macosX64` ターゲットを宣言（macOS 13+ なので `linkerOpts` で `-mmacosx-version-min=13.0`）。
3. `gradle wrapper` をコミットし、開発者・CI で同一バージョンを使用。
4. `.gitignore` に `kmp/.gradle/`, `kmp/build/` を追加。
5. ローカルで `./gradlew :kmp:tasks` が成功することを確認。

**完了条件**: `./gradlew :kmp:build` がノーソースで通る（成果物は空）。Swift 側のビルドに影響なし。

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

**完了条件**: `./gradlew :kmp:compileKotlinMacosArm64` が通る。

### F3: テスト同等化
- 既存 `SettingsStoreTests` の各 `@Test` ケースに対し、**同じ振る舞いを検証する `kotlin.test`** ケースを 1 対 1 で書く。
  - `defaultSwitchMode is auto` → `assertEquals(SwitchMode.Auto, store.switchMode)`
  - `switchMode persists to UserDefaults` → InMemory な `KeyValueStore` 実装でキー `"switchMode"` の値を確認
- `commonTest` で書いた純粋ロジックテストに加え、`macosTest` で `NSUserDefaultsKeyValueStore` 実装の往復テストを 1 本追加（`SettingsStoreTests` の `makeIsolatedDefaults()` と同等の suite name 戦略）。
- `SwitchModeTests` 側も同様に再記述。

**完了条件**: `./gradlew :kmp:macosArm64Test` が green。Swift 側 `swift test` も green を維持。

### F4: Swift 連携
1. `kmp/build.gradle.kts` に XCFramework 出力タスクを追加（`XCFramework().add(...)`）。
2. `Package.swift` に `.binaryTarget(name: "AutoDarkModeKMP", path: "kmp/build/XCFrameworks/release/AutoDarkModeKMP.xcframework")` を追加し、`autoDarkMode` 実行ターゲットの `dependencies` に加える。
3. Swift 側 `SettingsStore` を、内部状態を Kotlin 製 `SettingsStoreLogic` に委譲する **薄いラッパ** にリファクタ。`@Published` プロパティの公開 API は変えない（既存テスト・既存 UI を保護）。
4. `Scripts/build-app.sh` の手前に `./gradlew :kmp:assembleAutoDarkModeKMPXCFramework` を呼び出すフックを追加。

**完了条件**:
- `./Scripts/validate.sh` 全体が green。
- 既存 Swift テストがすべて green（書き換えていない）。
- アプリ起動時の挙動・設定の互換性（既存ユーザの UserDefaults キー / 値）を維持。

### F5: CI 統合
1. `.github/workflows/` に Gradle セットアップ（`actions/setup-java@v4` + `gradle/actions/setup-gradle`）と Kotlin/Native キャッシュ（`~/.konan`）を追加。
2. PR ジョブで `./gradlew :kmp:check` を必須化。
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
