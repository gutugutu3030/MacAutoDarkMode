# NSStatusItem プロトタイプ評価メモ

本ドキュメントは、UI の KMP 化を再評価する際の **最初の技術ゲート** を記録する。
対象は SwiftUI 設定画面ではなく、現行 UX の土台である `NSStatusItem` ベースのメニューバー常駐部分である。

## 目的

Kotlin/Native から AppKit を直接用いて、最小限のメニューバーアプリの殻を成立させられるか確認する。

成功条件は次の 4 点に限定する。

- Kotlin/Native で macOS 実行バイナリを生成できる
- `NSStatusItem` を作成し、メニューバーに表示できる
- `NSMenu` を開き、静的なメニュー項目を表示できる
- `Quit` メニューから終了できる

## 実装場所

- 現行 runtime の概要: [`../../README.md`](../../README.md)
- 実行コード: [`../../src/macosArm64Main/kotlin/com/github/gutugutu3030/autodarkmode/prototype/Main.kt`](../../src/macosArm64Main/kotlin/com/github/gutugutu3030/autodarkmode/prototype/Main.kt)

当初このコードは独立 prototype として検証していたが、現在は repository root の Gradle project に統合されている。

## 初回検証結果

ローカル macOS 環境で以下を確認した。

- `./gradlew linkDebugExecutableMacosArm64` が成功する
- 生成物 `build/bin/macosArm64/debugExecutable/autoDarkMode.kexe` が起動する
- 起動後、`pgrep -fl 'autoDarkMode\\.kexe|autoDarkMode'` でプロセス常駐を確認できる

今回は headless な確認に留めており、**メニューバー上の見た目と操作感は手動確認が必要** である。

## 第 2 段検証結果: 動的メニュー更新

次段階として、当時の Swift `StatusBarController` が持っていた更新責務の一部を Kotlin 側で再現した。

- Off / Auto / Manual の mode 項目を Kotlin の action で切り替えられる
- lux, appearance, message 行を状態に応じて更新できる
- Auto mode のときだけ threshold 行を表示し、それ以外では隠せる
- icon と tooltip を mode / sensor 状態に応じて更新できる
- 状態変更を即時反映せず、zero-delay timer で 1 回の presentation 更新に集約できる

ローカル macOS 環境で以下を確認した。

- `./gradlew linkDebugExecutableMacosArm64` が、動的更新版の実装でも成功する
- Kotlin/Native の `@ObjCAction` と `NSMenuItem` target/action を使って menu event を受け取れる
- `NSTimer` ベースの疑似更新ループで、Kotlin 側が menu presentation の所有権を持てる

## 第 3 段検証結果: 複数イベント源の流入と更新集約

さらに、当時の `BrightnessKeyMonitor` と `AutoSwitchEngine` に相当する 2 系統の入力を、Kotlin 側 runtime へ別々に流し込んだ。

- BrightnessKeyMonitor 相当: 高頻度の key up/down 風イベント
- AutoSwitchEngine 相当: sensor availability, lux, appearance, message を持つ集約イベント
- バースト時: engine event の直後に brightness event を重ねて、同一 flush へ畳めるかを確認

ローカル macOS 環境で、起動ログから以下を確認した。

- 継続実行中の flush ログが出力される
- 通常時は `1 mutation(s)` の flush が多い
- バースト時には `Flush 6: 3 mutation(s)` のように、複数 mutation が 1 回の presentation 更新へ集約される

この結果から、**少なくとも PoC レベルでは、複数イベント源が Kotlin 側へ流入しても presentation 集約は直ちには破綻しない** と判断できる。
一方で、これはあくまで timer ベースの模擬入力であり、実際の権限イベント、UserDefaults 変更通知、AppKit の open menu 中の RunLoop mode 差分まではまだ評価していない。

一方で、この時点でも **現行実装と同等の保守性・安定性が得られるとはまだ言えない**。
特に、Swift 側の `scheduleUpdatePresentation()` は RunLoop mode を意識した集約をしているのに対し、この PoC は zero-delay timer による近似に留まる。

## 第 4 段検証結果: 永続設定変更からの反映

次の 1 本として、production の `SettingsStore` に寄せた更新経路を prototype に追加した。
対象は、設定画面相当の入力そのものではなく、**永続設定の変更がメニューバー表示へ戻ってくる経路** である。

- Kotlin menu action から `NSUserDefaults` の mode / threshold 値を書き込む
- `NSUserDefaultsDidChangeNotification` を受けて Kotlin 側が snapshot を再読込する
- 再読込した値から mode と threshold 行を更新し、通常の presentation flush へ流し込む

この段階で確認したいことは次である。

- 設定反映が direct mutation ではなく通知経路でも崩れないこと
- mode と threshold の更新が同じ state 集約点へ戻せること
- settings 起点の更新も、brightness / engine 起点の更新と同様に coalescing の対象にできること

この検証が通るなら、prototype は `SettingsView -> SettingsStore -> StatusBarController` に近い 1 本の更新経路を部分的に再現したと言える。
一方で、これはまだ SwiftUI binding, `@Published`, open menu 中の RunLoop mode, launch-at-login を再現したものではない。

## この段階で分かること

- Kotlin/Native から AppKit の基本 API を叩くこと自体は可能
- `NSStatusItem` を Kotlin 側で所有する最小構成は作れる
- 状態を Kotlin 側に集約し、menu item の state / hidden / icon / tooltip を更新するところまでは成立する
- UI KMP 化の議論を続ける最低条件は満たした

## まだ分からないこと

このプロトタイプは、UI 全面移行の難所をまだ解決していない。

- 動的メニュー更新を MainActor / RunLoop 相当で安定運用できるか
- `NSMenuItem` の action/target を増やしたときに Kotlin 側の保守性が維持できるか
- 現行の `StatusBarController` が行っている状態集約を Kotlin 側で自然に書けるか
- 実際の macOS イベント源を接続したときも、timer ベース PoC と同じように flush 集約が維持できるか
- 永続設定変更が `SettingsStore` 相当の経路で安定して反映されるか
- 権限導線、ショートカット、設定ウィンドウとの接続を含めた同等 UX を作れるか

つまり、この PoC は **前進条件の確認** であって、全面移行の妥当性を示すものではない。

## 次に見るべきこと

この PoC は、その一部までは前進した。
次に見るべき点は、さらに本番に近い責務を Kotlin 側へ寄せたときに破綻しないかである。

- `AXIsProcessTrustedWithOptions` など権限導線を含むメニュー項目の反映
- `BrightnessKeyMonitor` や `AutoSwitchEngine` 相当のイベント流入時に更新が破綻しないこと
- 設定ウィンドウや永続化と接続したときも UI 所有権が複雑化し過ぎないこと

ここで破綻するなら、UI KMP 化はその時点で止める判断材料になる。
