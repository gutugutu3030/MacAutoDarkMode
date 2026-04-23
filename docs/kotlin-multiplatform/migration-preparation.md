# Kotlin Multiplatform 移行準備メモ

本メモは、KMP menubar PoC の技術ゲートを通過した後に、production 実装へ入る前の準備項目を固定するためのもの。

## 現時点の判断

- `NSStatusItem` の最小所有は Kotlin/Native で成立した
- 動的 menu 更新も Kotlin 側で再現できた
- BrightnessKeyMonitor 相当と AutoSwitchEngine 相当の複数イベント源を流し込んでも、PoC では flush 集約が維持された

したがって、次の一手は UI 全面移行ではなく、**Swift と Kotlin の責務境界を保ったまま shared 化できる部分から着手すること** になる。

## 最初の実装対象

最初に移す対象は、AppKit 実体ではなく presentation state の組み立てロジックに限定する。

- mode ごとの icon 名
- tooltip 文言
- threshold 行の表示可否
- appearance 表示文言
- 最終メッセージ選択ルール

これらは当時の `StatusBarController` の `updatePresentation()` に集まっていたため、shared 化の候補として最も切り出しやすかった。

## 先に固定する接続面

Swift から Kotlin へ渡す入力値は次に絞る。

- switchMode
- lastReadingLux
- sensorAvailable
- source
- lastKnownAppearance
- lastActionDescription
- lastError
- darkThresholdLux
- lightThresholdLux

Kotlin から Swift へ返す出力値は次に絞る。

- symbolName
- tooltip
- appearanceTitle
- thresholdTitle
- thresholdHidden
- resolvedMessage

## 同等 UX の最低条件

shared 化へ進む前に、少なくとも次を acceptance criteria として固定する。

- menu bar 側の icon, tooltip, threshold row が単一の state から決定されること
- 設定変更が再起動なしでメニューバー表示へ伝播すること
- open menu 中でも presentation 更新が破綻しないこと
- permission support message, sensor availability, last action / error を欠落なく表現できること
- launch-at-login と既存 UserDefaults キー互換を壊さず rollback できること

この条件を満たせない場合、Kotlin の適用範囲は presentation state mapper までに限定する。

## 着手順

1. root Gradle project に shared source set を追加する
2. presentation state DTO と mapper を Kotlin 側へ実装する
3. Swift 側に thin adapter を置き、既存 AppKit UI をそのまま使って結果だけ読む
4. 既存 Swift テストに加えて、mapper の Kotlin テストを追加する
5. その後にだけ、UI ownership の再評価へ進む

現時点の Kotlin 側検証実装は、ここでいう「設定変更が再起動なしでメニューバー表示へ伝播すること」を、`NSUserDefaultsDidChangeNotification` を経由する 1 本の更新経路として追加検証する段階にある。

## まだ移さないもの

- `NSStatusItem`, `NSMenu`, `NSMenuItem` 自体の production ownership
- Accessibility permission prompt
- launch-at-login
- ALSBridge や private framework 依存
- Settings window

## 着手可否の目安

次の条件を満たすなら、移行準備から実装着手へ進める。

- Swift build が現状どおり green を維持する
- root Gradle project の shared source set がアプリ本体と疎結合に保たれる
- shared 化の最初の slice が `StatusBarController` の presentation state に限定される
- rollback が 1 コミット単位で可能