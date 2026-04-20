# 移行着手の判断記録

本ドキュメントは Issue「kotlin multiplatform への実装変更」**任意要件 3「移行を進める」** に対する判断記録である。

## 結論

**現時点では移行に着手しない（保留）。**

## 判断の根拠

詳細は以下を参照。

- 移行可能性: [`feasibility.md`](./feasibility.md)
- 移行プラン: [`migration-plan.md`](./migration-plan.md)

要点を再掲する。

1. **マルチプラットフォーム化の動機が無い**
   本アプリは macOS 専用機能（環境光センサー / システム外観切替）に依存している。
   Issue 内でも他 OS（iOS / Android / JVM 等）への展開意図は示されていない。
   KMP の最大の利点である「コード共有」が活きない。
2. **PrivateFramework 依存は KMP 化しても消えない**
   `BezelServices` / `AppleLMUController` への呼び出しは Obj-C ランタイム経由の薄いシム（現 `Sources/ALSBridge/ALSBridge.m`）を必要とし、
   Kotlin/Native の `cinterop` でも同等の Obj-C/C コードを書き直すだけになる。
3. **UI の全面再実装が必要**
   SwiftUI 製の設定ウィンドウ・`NSStatusItem` ベースのメニューバー UI を Kotlin 側へ移すと、
   AppKit cinterop もしくは Compose Multiplatform for macOS への全面書き換えになる。
   現アプリの規模（Swift 約 1,800 行）に対し、新規アプリ開発に近い実装コストとなる。
4. **CI / 配布ワークフローへの影響が大きい**
   Gradle + Kotlin/Native ツールチェイン（`~/.konan` で約 1〜2 GB）と XCFramework ビルドを CI に追加する必要があり、
   現状の単純な `swift build` / `xcodebuild` 流のパイプラインを複雑化させる。
5. **テスト互換性は「要件互換」までが上限**
   Swift Testing (`@Test`, `#expect`) と `kotlin.test` は API が異なるため、
   「同じテストがそのまま走る」状態は原理的に作れない。要件レベルの再記述で十分なら、
   そもそも現行 Swift テストで要件は満たされている。

以上より、**着手による得失バランスはマイナス** と判断する。

## 着手するための前提条件（再評価トリガ）

以下のいずれかが満たされた時点で、本判断を見直す。

- [ ] iOS / Android / JVM デスクトップなど、**macOS 以外のターゲット** にロジックを再利用する具体計画ができた
- [ ] Compose Multiplatform for macOS が `NSStatusItem` ベースのメニューバー常駐アプリを公式に十分サポートする状態になり、
      かつ本プロジェクトのメンテナがそれを採用する意思を示した
- [ ] 環境光センサー周辺の Apple 公式 API が公開化され、PrivateFramework 経由の経路が不要になった
- [ ] テスト・ロジック共通化の必要性が CI / 開発体験の観点から具体的に発生した（例: 同じロジックを別リポジトリで再利用したい等）

再評価の際は、本ディレクトリの [`migration-plan.md`](./migration-plan.md) §1 のフェーズ表をそのまま着手手順として使用できる。

## なぜ「コミットを分けて」記録するか

Issue では「3 つのタスクについて、必ずコミットを分けて push する（処理が途中で止まっても、前段階のコミットは見れるようにしたい）」と要求されている。
本判断は **任意要件 3 に対する明示的な “着手しない” の回答** であり、
履歴上「タスク 3 を検討した結果として保留判断を残した」ことが確認できるよう、
独立したコミットとしてこのドキュメントを追加する。
