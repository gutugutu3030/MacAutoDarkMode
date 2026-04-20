# Kotlin 完全置換ロードマップ

本ドキュメントは、現行 Swift 実装を将来的に廃止し、Kotlin/Native ベースの実装へ置き換える場合の実行計画を整理するための roadmap である。

この文書は既存の [migration-decision.md](./migration-decision.md) を上書きするものではない。既存文書は「その時点での保留判断」を記録した履歴として維持し、本ドキュメントはその後に再検討した結果としての **完全置換トラック** を独立に記録する。

## 前提

- Swift は最終的に廃止対象とする
- ただし、Apple の private API や system integration のために必要最小限の Obj-C/C シムは許容する
- 置換の完了条件は、現行 Swift 実装と同等の機能を Kotlin 側で成立させることとする
- 現行の `prototypes/kmp-menubar-poc/` は本番置換候補の出発点ではあるが、そのまま production へ昇格できる状態ではない

## 関連文書との位置づけ

- 技術的可否の調査: [feasibility.md](./feasibility.md)
- 過去の保留判断: [migration-decision.md](./migration-decision.md)
- ハイブリッド移行の履歴: [migration-plan.md](./migration-plan.md)
- prototype の評価記録: [nsstatusitem-prototype.md](./nsstatusitem-prototype.md)

## 致命欠陥ゲート

完全置換では、機能差分の実装より先に以下の blocker を解消する。

### 1. RunLoop mode 差分

現行 Swift 実装は open menu 中でも更新が stale にならないよう、RunLoop mode を意識して presentation 更新を集約している。
prototype の zero-delay timer ベース更新がこれを再現できない場合、完全置換は停止する。

合格条件:

- menu を開いた状態で mode 切替、threshold 変更、sensor event が入っても表示が stale にならない
- 更新集約が burst 時にも崩れない

### 2. 単一 state owner

Kotlin 側が menu、settings、monitor、appearance 切替の唯一の mutation 境界を持つこと。
`NSUserDefaultsDidChangeNotification` を主経路にした二重同期は採らない。

合格条件:

- state の正本が Kotlin 側に 1 つだけ存在する
- settings 更新と UI 更新が同一 state から導かれる

### 3. settings 通知依存の排除

永続設定の変更通知は補助経路に留め、主経路は Kotlin state の直接更新と永続化に置く。

合格条件:

- notification がなくても state と UI が同期する
- production の UserDefaults キー互換を維持する

### 4. Accessibility permission 導線

manual mode で必要な brightness key monitoring は、permission denied 状態でもユーザに説明可能でなければならない。

合格条件:

- permission required 状態を UI に表現できる
- prompt を出す条件と出さない条件が明確である

### 5. launch-at-login の成立性

Kotlin 版でも app bundle path を解決し、LaunchAgent を正しく作成・削除できる必要がある。

合格条件:

- bundle から起動したときだけ startup toggle を有効化できる
- LaunchAgent の生成・削除・再読込が成立する

## Commit 単位ロードマップ

### commitA: Kotlin 単一 state owner 化

目的:

- prototype に散在している menu/state/settings 反映を production 向け state store と controller に分離する
- Kotlin 側を唯一の mutation 境界にする

主な対象:

- `prototypes/kmp-menubar-poc/.../Main.kt`
- `prototypes/kmp-menubar-poc/.../PrototypePersistedSettings.kt`

完了条件:

- menu、settings、appearance 反映が単一 state から決まる
- 二重同期のための暫定実装が消えている

### commitB: RunLoop / menu 更新戦略の本番化

目的:

- Swift の `StatusBarController` が持つ open menu 中の更新保証を Kotlin 側で再現する

主な対象:

- `prototypes/kmp-menubar-poc/.../Main.kt`
- `Sources/autoDarkMode/UI/StatusBarController.swift` を比較基準として参照

完了条件:

- stale 表示が解消されている
- burst 更新が 1 回の flush に安定して集約される

### commitC: settings 永続化の production 化

目的:

- prototype 専用キーを廃止し、本番 UserDefaults キーへ統一する
- 既存の shared settings logic を prototype 側へ統合する

主な対象:

- `kmp/src/commonMain/.../SettingsStoreLogic.kt`
- `kmp/src/macosMain/.../NSUserDefaultsKeyValueStore.kt`
- `prototypes/kmp-menubar-poc/.../PrototypePersistedSettings.kt`

完了条件:

- Swift 版と同じ key / rawValue 契約で round-trip する
- notification は補助経路へ格下げされている

### commitD: 外観切替と auto mode parity

目的:

- 自動切替の production logic を Kotlin 側へ移す

主な対象:

- `Sources/autoDarkMode/Appearance/AppearanceController.swift`
- `Sources/autoDarkMode/Application/AutoSwitchEngine.swift`
- Kotlin 側の auto switch 実装と tests

完了条件:

- hysteresis
- required samples
- cooldown
- error handling

以上が Kotlin 側で再現されている

### commitE: manual mode parity

目的:

- manual mode の実輝度監視と brightness key monitoring を Kotlin 側へ移す

主な対象:

- `Sources/autoDarkMode/AmbientLight/ScreenBrightnessMonitor.swift`
- `Sources/autoDarkMode/Application/BrightnessKeyMonitor.swift`

完了条件:

- F1 / F2 decode
- 0.35 秒長押し
- release-after-max
- Accessibility permission prompt

以上が Kotlin 側で成立している

### commitF: settings UI と launch-at-login parity

目的:

- SwiftUI settings window を Kotlin/AppKit 側へ置き換える
- startup toggle と LaunchAgent 管理を実装する

主な対象:

- `Sources/autoDarkMode/UI/SettingsView.swift`
- `Sources/autoDarkMode/UI/SettingsWindowController.swift`
- `Sources/autoDarkMode/Application/LaunchAtLoginManager.swift`

完了条件:

- mode picker
- threshold slider
- current value capture
- startup toggle
- LaunchAgent 管理

以上が Kotlin 側で成立している

### commitG: CLI parity

目的:

- calibration 用の sample / watch 導線を Kotlin executable に統合する

主な対象:

- `Sources/autoDarkMode/Application/CalibrationCLI.swift`

完了条件:

- Swift CLI を使わず、Kotlin executable だけで sample / watch が使える

### commitH: Kotlin app bundle shell

目的:

- Kotlin release executable から `.app` を組み立てる build shell を追加する

主な対象:

- `Scripts/build-app.sh`
- 新規 `Scripts/build-kotlin-app.sh`
- `AppResources/Info.plist`

完了条件:

- arm64 の `.app` をローカル生成できる
- ad-hoc codesign された app bundle が起動できる

### commitI: release workflow 切替

目的:

- release automation を Kotlin app bundle 前提に切り替える

主な対象:

- `.github/workflows/release.yml`
- `.github/README.md`
- `README.md`

完了条件:

- tag push で Kotlin app bundle の zip と sha256 が生成される
- GitHub Release 公開まで完結する

### commitJ: Swift 実行系 cutover

目的:

- release path から Swift 実装を外し、Kotlin を唯一の本番実装にする

主な対象:

- `Package.swift`
- `Sources/autoDarkMode/`
- `Scripts/validate.sh`

完了条件:

- Kotlin 実行系だけで主要ユースケースが成立する
- Swift 実装が本番導線から外れている

### commitK: 評価と最終判断記録

目的:

- 完全置換の結果、残余リスク、撤退条件を docs に反映する

主な対象:

- `feasibility.md`
- `migration-decision.md`
- 本 roadmap 文書

完了条件:

- 完全置換の成否と、今後残る技術負債が追記されている

## リリース切替の方針

release shell と workflow の切替は、機能 parity が主要部分まで揃った後段に置く。先に shell だけを作っても、完全置換文脈では本番相当の意味を持たないためである。

最小リリース可能物の定義:

- arm64 の Kotlin menu bar app が `.app` として起動できる
- auto / manual / settings / startup / CLI の主要機能が Swift 同等で成立している
- GitHub Release から zip 配布できる

Developer ID signing、notarization、universal binary 化はその次段で扱う。

## 撤退条件

以下のいずれかが解消できない場合、Swift 完全廃止方針は再評価する。

- open menu 中の更新保証を Kotlin 側で安全に再現できない
- Kotlin 側の単一 state owner 化が成立せず、二重同期が残る
- manual mode parity が native shim を含めても不安定である
- launch-at-login と permission 導線が production 品質に届かない

## 補足

docs への最初の反映では、本ファイルの追加だけに留める。既存 docs の本文更新や cross-link 追加は、次段の commit で必要最小限に行う。