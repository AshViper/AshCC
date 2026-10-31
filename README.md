# AshCC

CC: Tweaked 用のペリフェラル MOD（Minecraft 1.21.1 / NeoForge 21.1.235 以降）。

## レーダー（`ashcc:radar`、周辺機器タイプ `radar`）

1ブロックで完結するセンサー。コンピューターに隣接させるか、有線モデムで繋ぐ。

- エンティティ（プレイヤー・モブ・アイテム・Create のコントラプション等）を検知
- **Sable の Sub-Level**（Create Aeronautics の機体）を検知（Sable が無くても動く。その場合は Sub-Level の検知が空になる）
- **機体に載せても動く**: 座標はワールド座標に直して返し、方位・仰角は機体の向きに合わせて回る
- 設置時にプレイヤーが向いていた方向が「前」。`local` 座標は x=右, y=上, z=前

### 座標・単位
- `x,y,z`: ワールド座標。エンティティは当たり判定の中心、Sub-Level は重心
- `vx,vy,vz`: 速度（ブロック/秒）
- `local`: レーダー基準の位置（x=右, y=上, z=前）。`localVelocity` も同様
- `azimuth`: 方位角（度、前=0、右が正）。`elevation`: 仰角（度、上が正）
- `distance`: 距離。Sub-Level は外接箱の最も近い点まで（中心までは `centerDistance`）

### 関数
| 関数 | 説明 |
|---|---|
| `getRange()` / `setRange(r)` | 探知半径（既定2048、上限なし） |
| `hasSable()` | Sable 連携が有効か |
| `getPosition()` | レーダーのワールド座標。機体上なら `subLevel`（機体ID）付き |
| `getOrientation()` | `right/up/forward` のワールド方向ベクトル |
| `toLocal(x,y,z)` / `toWorld(x,y,z)` | ワールド座標 ⇔ レーダー座標 |
| `getSelf()` | 載っている機体の情報（Sub-Level の表）。地上なら nil |
| `scan([range])` | `{ entities = {...}, subLevels = {...} }`（距離順） |
| `scanEntities([range], [filter])` | エンティティのみ。filter = `{ type = "minecraft:zombie" or {...}, player = bool, living = bool, name = "部分一致" }` |
| `scanPlayers([range])` | プレイヤーのみ |
| `scanSubLevels([range])` | 機体のみ（自分が載っている機体は除外） |
| `getContact(id)` | 1件を再取得（追跡用）。範囲外・消滅で nil |
| `intercept(id, speed, [gravity])` | 等速で動く目標への見越し射撃解。`{x,y,z, time, direction, local, azimuth, elevation, yaw, pitch}`。届かなければ nil |
| `setWatch(enabled, [opts])` / `getWatch()` | 監視モード。opts = `{ interval = tick, entities = bool, subLevels = bool, filter = {...} }` |

### エンティティの表
`kind="entity"`, `id`(UUID), `type`, `name`, `isPlayer`, `isLiving`, `health`, `maxHealth`, `yaw`, `pitch`, `feet`(足元座標), `vehicle`(乗り物のID), `subLevel`(乗っている/立っている機体のID) ＋共通項目

### Sub-Level の表
`kind="sublevel"`, `id`, `name`, `mass`, `orientation{w,x,y,z}`, `angularVelocity`, `bounds{minX..maxZ}`, `size{x,y,z}`, `posePosition`, `centerDistance`, `iff{code,callsign}`（IFFトランスポンダーを積んでいる機体だけ）＋共通項目

`scanSubLevels` にはチャフ（下記）のエコーも同じ形で混ざる。`getSelf()` にも自機の `iff` が付く。

### イベント（監視モード）
- `radar_contact`, 周辺機器名, 表 — 範囲に入った
- `radar_lost`, 周辺機器名, id, kind — 範囲から出た/消えた

監視の設定はブロックに保存され、チャンクの再読み込み後も続く（コンピューターが繋がっている間だけ動く）。

### 例
- `lua/radar_view.lua` — モニターにレーダースコープを描く（地形の背景つき）
- `lua/radar_3d.lua` — 高精細モニターに地形の 3D マップと機体・プレイヤーを描く（ドラッグで回転、スクロールで拡大、ボタンで範囲・高さ倍率・北上/機首上・色）
- `lua/radar_alert.lua` — 監視イベントで出入りを表示し、機体には見越し角を出す
- `lua/flap_test.lua` — フラップベアリングの試験。繋がっている全フラップ（リンクハブ経由も可）について組み立て→速度設定→角度スイープ（所要時間と実測速度を確認）→範囲外の角度→高速移動→分解 を自動で行い、PASS/FAIL を `flap_test.log` に書く
  - `flap_test` / `flap_test auto <名前> [--keep]`（--keep で組み立てたまま残す）
  - `flap_test manual [名前]` — キーで操作（←→ 角度、↑↓ 速度、1-9 刻み、Space 0°、a 組立、d 分解、Tab 次のフラップ、q 終了）
  - `flap_test wave [振幅] [周期]` — 全フラップを正弦波で振る（機体の反応を見る用、Ctrl+T で止めると 0° に戻す）
- `lua/thruster_hover.lua` — 繋いだ全スラスターで高度維持（CC: Sable の `sublevel` API を使用）

## シーカー（`ashcc:seeker`、周辺機器タイプ `seeker`）

向いている方向（視線）の円錐内で、**視線の中心に最も近い** Sable の機体を1つ返す。関数は `seek` だけで、引数を省略できる。

```lua
local seeker = peripheral.find("seeker")
local t = seeker.seek()            -- 設定の既定値（15°, 512 ブロック）
local t = seeker.seek(30)          -- 視線から 30° 以内
local t = seeker.seek(30, 200)     -- 30° 以内かつ 200 ブロック以内
if t then print(t.name, t.offAxis, t.distance, t.azimuth, t.elevation) end
```

- `angle`: 視線からの半角（度、0〜180）。`distance`: 目標の重心までの距離の上限
- 戻り値: レーダーの Sub-Level の表と同じ項目（`id, name, x,y,z, vx,vy,vz, local, azimuth, elevation, mass, bounds …`）＋ `offAxis`（視線からのずれ、度）。見つからなければ `nil`
- 「中心に近い」= `offAxis` が最小（同じなら近い方）。判定は機体の重心で行う
- 設置時にプレイヤーが見ていた方向（上下も可）を向く。`local`/`azimuth`/`elevation` はシーカー基準（x=右, y=上, z=前。真上・真下向きのときは y=北）
- 自分が載っている機体は対象外。機体に載せれば機体と一緒に向きが変わる。Sable が無いとフレア以外は見つからない
- 燃えているフレア（下記）も機体と同じ形で返る。出たてのフレアは実際より `flareBias`（既定 10°）だけ中心に近いとみなされ、燃え尽きるにつれて差が 0 になる

テストプログラム `lua/seeker_test.lua`（シーカーの前のどこかに Sable の機体を置いてから実行）:
- `seeker_test [名前]` — 引数の省略形3通り、不正な引数の拒否、結果の項目、`offAxis` と `local` の一致、見つけた機体の角度・距離を少しだけ狭めると外れること、24通りの角度×距離で結果が制限を守ること、円錐を広げても `offAxis` が大きくならないこと を確認（`seeker_test.log`）
- `seeker_test track [角度] [距離] [名前]` — 照準表示。画面の端＝円錐の縁として目標の位置を `#` で表示（モニターがあればモニターに）

## レーザー（`ashcc:laser`、周辺機器タイプ `laser`）

どの方向にも光線を出し、当たったブロックを焼き切り、エンティティを傷つける。**Sable の機体に載せても、機体を撃っても動く**。

- 向き: ブロックに前は無く、全方向（球全体）を狙える。照準は `yaw`（北=0、東が正、-180〜180）と `pitch`（上が正、-90〜90）。どの面も同じ見た目で、置く向きは関係ない
  - 機体の上では「北・上」は機体自身の北・上（機体と一緒に回る）。`aimAt(x, y, z)` はワールド座標なので、普段はこちらを使えばよい
  - 照準は現在の向きから目標の向きへ最短の弧で `aimSpeed`(180°/秒) で回る（真上付近でも横に振れない）
  - 光線はブロックの中心からその方向の面を抜けて出る。隣のブロックにふさがれている方向には撃てない（自機のブロックなら焼かずに止まる）
- 既定はレッドストーン制御（信号が入っている間撃つ）。Lua で `fire`/`stop` を呼ぶとコンピューター制御に切り替わる
- ブロック: 毎 tick `blockPower`(0.5) ずつ硬さを削り、硬さに達すると壊れる（石 3 tick、鉄ブロック 10 tick、黒曜石 5 秒）。岩盤など壊せないブロックは止めるだけ。既定ではドロップしない
- エンティティ: 光線上の最初のエンティティに `entityDamage`(4) ダメージと着火（後ろのブロックは守られる）
- 連続 `maxFireSeconds`(10) 秒で過熱して止まり、冷え切る（`cooldownSeconds`=5 秒で全快）まで撃てない

### Sable との連携
- **機体上**: 光線の始点・向きは機体と一緒に動き・回る（`aimAt` はワールド座標で指定できるので、機体が回っても毎 tick 呼べば目標を追う）
- **機体を撃つ**: Sable のレイキャストで他の機体のブロックにも当たり、焼き切れる（`getHit().subLevel` に機体ID）。当たった点に `impactForce`(100、質量×ブロック/秒²) の力で機体を押す（中心を外せば回転もする）
- **反動**: 撃っている間、自分の機体を同じ力で逆向きに押す（`recoil`）。自分の機体に当たったときは内力なので押さず、既定では壊さない（`damageOwnSubLevel`）

| 関数 | 説明 |
|---|---|
| `fire([秒])` | 撃つ（省略で `stop` まで）。過熱中は `false, "overheated"` |
| `stop()` | 止める |
| `isFiring()` | いま光線が出ているか |
| `setAim(yaw, pitch)` | 照準（度）。yaw は -180〜180 に折り返し、pitch は -90〜90 に制限した値を返す |
| `aimAt(x, y, z)` | ワールド座標を狙う。`ok, yaw, pitch`（`ok=false` はレーザーのブロック自身の中を指定したときだけ） |
| `getAim()` / `getTargetAim()` / `isAimed()` | 現在・目標の照準 / 到達したか |
| `getDirection()` | 光線のワールド方向 `{x,y,z, origin={x,y,z}}`（origin は光線の始点） |
| `trace()` | 撃たずに、いまの照準で光線がどこまで届くか（距離計） |
| `getHit()` | 直前の tick に当たったもの（撃っていなければ nil） |
| `getHeat()` / `isOverheated()` | 熱 0..1 / 過熱中か |
| `getRange()` | 光線の長さ（設定値） |
| `getSubLevel()` | 載っている機体のID（地上なら nil） |
| `setRedstoneControl(b)` / `isRedstoneControl()` / `getRedstoneInput()` | レッドストーン制御 |

当たりの表（`trace` / `getHit`）: `type`（`"block"` / `"entity"` / `"none"`）, `x,y,z`（光線の終点・ワールド座標）, `distance`, `block`（ブロックID）, `subLevel`（機体ID）, `entity = {id, type, name}`, `progress`（`getHit` のみ、焼き切りの進み 0..1）

イベント: `laser_break`, 名前, ブロックID, x, y, z, 機体ID(nil可) — ブロックを壊した / `laser_overheat`, 名前 / `laser_ready`, 名前 — 冷えて撃てるようになった

```lua
local laser = peripheral.find("laser")
laser.aimAt(100, 70, -20)
laser.fire(2)          -- 2 秒撃つ
print(laser.getHit())
```

テストプログラム `lua/laser_turret.lua`:
- `laser_turret [名前]` — 照準の制限・`aimAt`・`trace`・`fire(1)` で止まること 等を確認（`laser_test.log`）
- `laser_turret track [距離] [名前]` — 自動砲塔。レーダー（無ければシーカー）で最寄りの機体を探し、狙えたら撃つ。過熱・ふさがれているときは自動で止める
- `laser_turret manual [名前]` — 矢印で照準、Space で発射切り替え、t で距離計

### 対空プログラム `lua/aa_laser.lua`（レーダー＋レーザー）

レーダーで空中の目標（Sable の機体と飛行モブ）を探し、繋がっている全レーザーで自動的に迎撃する。レーダー1台とレーザー何台でも（リンクハブ経由も可）。モニターがあれば状況を表示する。

```
aa_laser [距離] [--hold] [--spread]
```
- `距離`: 探知距離（既定 1024）。`--hold`: 撃たない状態で起動（Space で発射許可）。`--spread`: レーザーごとに別の目標を狙う（既定は全レーザーが最優先の目標へ）
- キー: Space 発射許可/保留、f 最優先の目標を味方に登録、c 味方の登録を消す、q 終了（全レーザー停止）。モニターをタッチでも発射許可/保留

仕組み:
- **目標**: レーダーの機体（自分の機体は除く）と `CFG.mobs`（ファントム・ガスト・ブレイズ）。底面がレーダーより `minAltitude`(4) ブロック以上高いものだけ。`aa_friends.txt` に書いた機体のID・名前は撃たない
- **IFF**: 自機と同じ IFF コードを送っている機体（`CFG.trustOwnCode`）と `CFG.friendCodes` のコードの機体は味方。自分のチャフ（自機と同じ質量・大きさのエコー）も撃たない。コードはなりすませるので、確実なのは `aa_friends.txt`
- **優先度**: 距離 −（接近速度 × 3 秒）が小さい順。近づいてくる目標が先
- **撃つのは光線が本当に目標に当たるときだけ**: 毎 tick `trace`/`getHit` で光線の終点を確認し、目標の機体（`subLevel`）やモブ以外（地面・味方の機体・自分の機体）に当たるなら撃たない（BLOCKED）
- **追尾**: レーダーとレーザーは別々のループで動き、各レーザーは毎 tick 照準し直す（スキャンからの経過時間はワールド速度で、照準の遅れ分は自機との相対速度で先読み）。機体に載せても動く
- **焼き切り**: ブロックは同じブロックに当て続けないと壊れないので、目標に当たったらその点（機体基準）に照準を固定する。焼き切れたら次の照準点（重心 → 外接箱の中心 → 各面の外側の層 → 四隅 → 箱の中のランダムな点）へ。同じ目標の複数のレーザーは別の照準点から始める（各レーザーが別のブロックを焼く）
- 一度も当たらないまま全部の固定の照準点を試した目標（地形や味方の陰）、または当たっていたが `giveUp`(6) 秒当たらなくなった目標は、そのレーザーでは `skipTime`(15) 秒無視する（同じ目標で繰り返すたびに倍、最大 60 秒）
- 探知距離（既定 1024）は光線の長さ（サーバー設定 `laser.range`、既定 256）より長くてよい。光線の届かない目標は一覧に出るだけで、届く距離に入ったら狙う
- **熱**: 熱が 0.9 で止め 0.5 まで冷えたら再開（過熱して 5 秒撃てなくなるのを避ける）
- ログ `aa_laser.log`（交戦・見失い・過熱。64KB で作り直し）

注意: 光線の終点は当たってから分かるので、目標の前に何かが割り込むと 1〜2 tick だけそこに当たることがある（0.5/tick なので石や機体の鉄は壊れないが、ガラス・葉・羊毛のような柔らかいブロックは壊れうる）。

設定はファイル先頭の `CFG` 表（探知距離・高度の下限・対象のモブ・先読み・熱の閾値など）。

検証用シミュレーター `tools/aa_sim/`（fengari で CC とレーダー・レーザーを模擬し、爆撃機・中空のリング・味方の陰の機体・低空の機体・地形の陰の機体を相手に実行。`npm install` → `node run.js ../../lua/aa_laser.lua`。`IFF=1` で味方を IFF コードだけで見分ける）

### UAV プログラム `lua/uav.lua`（反重力コア＋レーダー＋レーザー）

反重力コアで機体を飛ばし、レーダーで敵味方を識別して、敵をレーザーで攻撃する無人機。機体に反重力コア・レーダー・レーザー（何台でも）・コンピューターを載せる（リンクハブ経由も可）。機体は北向きで組み立てる（コアの yaw 0 = 組み立て時の前）。

```
uav [距離] [--hold] [--home]
```
- `距離`: 探知距離（既定 1024）。`--hold`: 撃たない状態で起動。`--home`: 今いる場所を新しいホーム（巡回の中心）にする（ホームは `uav_home.txt` に保存され、再起動しても同じ）
- キー: Space 発射許可/保留、h その場で旋回待機/再開、r ホームへ戻る、f 一番近い機体を味方に登録、e 敵に登録、c 登録を消す、l ゲーム内学習の ON/OFF、x 学習した分を忘れて組み込みの脳に戻す、q 終了（その場でホバーし、全レーザー停止）。モニターをタッチでも発射許可/保留
- 必要なのは `uav.lua` 1 ファイルだけ（学習済みの脳はファイル末尾に埋め込み済み）。ゲーム内で学んだ脳は `uav_brain.dat` に保存され、次回はそちらを使う

識別（`uav_marks.txt` の登録が最優先）:
- **味方**: 味方に登録した機体、自機と同じ IFF コード、`CFG.friendCodes` のコード、自分のチャフ
- **敵**: 敵に登録した機体、`CFG.hostileCodes` のコード、`CFG.mobs` のモブ（レーザーのみ）、それ以外の機体で `minTargetSpeed`(1) ブロック/秒より速く動いているもの（`CFG.unknown = "ignore"` で無効）。一度敵と判定した機体は、遅くなったり IFF を切ったりしても敵のまま
- **死亡（DEAD）**: 味方以外で `deadTime`(3) 秒動いていない機体（速さが `minTargetSpeed` 未満。真下に落ちているだけの残骸も動いていないとみなす）。撃墜済み・無人とみなし、敵登録や敵コードの機体でも撃たず追わない。また動き出したら元の判定に戻る
- **不明**: 動いている IFF 無しの機体で `CFG.unknown = "ignore"` のときや、見つけてから `deadTime` 秒たっていない止まった機体。表示するだけで撃たない

飛行（戦闘機らしく。約 10 回/秒、`setVelocity` と `setRotation`）:
- プログラムの中で簡単な飛行機（速度・方位・上昇角）を飛ばし、その速度をコアに送る。機体は常に機首の向きに進み、横滑り・後退・ホバーはしない
- **旋回**: バンクして曲がる。旋回率 = `gravity`(9.8)·tan(バンク)/速度 なので速いほど大回り。バンク最大 `maxBank`(60)°、`rollTime`(0.8) 秒かけて滑らかに入れる・戻す（右旋回で右下がり）。上昇・降下は機首の上げ下げ（`maxClimb` 30° / `maxDive` 35°、`pitchRate` 30°/秒）。`minSpeed`(12) 未満にはならず、地上からは離陸滑走
- コアの上限は起動時に `setLimits` で設定（速度 30、加速度 40、回転 120°/秒）

**AI パイロット**（機械学習。操縦・戦術・狙う敵を AI が決める）:
- 0.5 秒ごとにニューラルネット（42 入力 → 64 → 64 → 13 出力、分岐型 DQN）が状況を見て、**バンク**（−60/−30/0/30/60°）・**上昇角**（−20/0/+20°）・**速度**（16/23/30）・**どの敵に集中するか**（2 機のどちらか。レーザーはその敵を優先）を選ぶ
- 見ているもの: 自機の速度・上昇角・旋回率・ホームの方向と距離・レーザーの熱・損傷、**地形**（真下と、前方 1/2/4 秒先・左右 35° の 2/4 秒先の地面までの余裕）、脅威の大きい敵 2 機（距離・方向・高低・接近速度・こちらへ向かっているか・損傷・地形の陰か）、前回の指令
- 地形はレーダーの `scanTerrain(192, 96, true)`（4 ブロック格子）を 1 秒ごとに取って使う。`scanTerrain` の無いレーダーでは「ホームの少し下が平らな地面」とみなす
- **安全装置（AI より優先）**: 今の進路で 3 秒以内に地面まで `gcasMargin`(6) ブロックを切りそうなら翼を水平にして全力上昇（上昇しても越えられないなら低い側へ旋回）。ホームから `fence`×1.3 を超えたらホームへ旋回。誤射防止（光線が目標に当たるときだけ撃つ）は従来どおり
- **事前学習**: `tools/uav_ai/`（Node.js）の戦闘シミュレーター（丘・山の地形、爆撃機・一撃離脱する敵戦闘機・浮遊砲台。レーザーは射程と地形の見通しが要る）で 200 万判断の強化学習（約 80 分）。報酬は与えた損傷 +1/ブロック、撃墜 +20、受けた損傷 −1.5/ブロック、墜落 −50、撃墜される −30、安全装置の作動 −1、圏外 −0.5
- 新しい 500 戦での比較（同じ安全装置付き）:

  | | 撃墜率 | 撃墜された | 墜落 | 受けた損傷/戦 |
  |---|---|---|---|---|
  | 手書きの一撃離脱 | 88.6% | 51 | 11 | 12.1 |
  | AI | **97.0%** | **12** | **2** | **9.0** |
- **ゲーム内学習**（`CFG.learn`、l で切り替え）: 実戦の結果（`laser_break` で与えた損傷、損傷を与えた敵が DEAD になるか近くで消えたら撃墜、自機の質量の減少で受けた損傷、安全装置の作動）で同じ報酬を計算し、直近 2000 判断から少しずつ学ぶ（SGD、学習率 0.0005、200 判断たまってから）。探索は各判断 3%。2 分ごとと終了時に `uav_brain.dat` へ保存。x で忘れる
- h（その場で旋回待機）・r（帰還）と、脳が無いときは手書きの操縦で飛ぶ
- 飛行機の数値（`maxSpeed` など）は AI が学んだ機体そのものなので、変えると学習と違う機体になる（変えたら再学習する）
- 照準・射撃・熱管理は `aa_laser` と同じ。ログ `uav.log`

再学習・確認（`tools/uav_ai/`、fengari は `../aa_sim` のものを使う）:
- `node train.js [判断数] [brain.json]` — 学習（20 万判断ごとに評価して一番良い重みを保存）
- `node validate.js brain.json 500` — 学習に使っていない戦闘で手書きの操縦と比較
- `node export.js brain.json` — 重みを `lua/uav.lua` の `BRAIN BEGIN`〜`BRAIN END` に書き込む
- `node parity.js brain.json` — `uav.lua` の `AI CORE` と学習側（`common.js`・`nn.js`）が同じ計算をするか確認（観測・ネットワーク・飛行モデル・安全装置）

検証用シミュレーター `tools/uav_sim/`（`aa_sim` の世界に反重力コアと動く自機を足したもの。敵2機・味方・駐機中の機体・止まって再び動く敵・圏外の機体。横滑り・最低速度・バンクの向き・DEAD への発射・地形（丘と `scanTerrain` のモック）からの余裕も数える。`node run.js ../../lua/uav.lua [tick数]`、`KEYS="600:h,900:h"` でキー入力。fengari は `../aa_sim` のものを使う）

## スラスター（`ashcc:thruster`、周辺機器タイプ `thruster`）

Sable の機体に取り付けると、その位置から機体を押す（重心から外れていれば回転力も生じる）。地上に置いても力は出ない。

- 向き: ノズル（噴射口）を設置したプレイヤー側に向けて置かれる。機体の下から見上げて置く → ノズル下向き＝上へ押す。スニークで逆向き
- 既定はレッドストーン制御（信号強度/15 が出力）。Lua で `setThrust` を呼ぶとコンピューター制御に切り替わる
- 力の単位は「質量×ブロック/秒²」（Sable の重力 ≈ 9.8 ブロック/秒²。質量 m の機体を浮かせるには約 9.8·m）
- 噴射中は光り、炎のパーティクルが出る

| 関数 | 説明 |
|---|---|
| `setThrust(t)` / `getThrust()` | 出力目標 0..1（コンピューター制御に切り替え） |
| `getCurrentThrust()` | 実際の出力（`throttleRate` で目標へ追従） |
| `getForce()` / `getMaxThrust()` | 現在の力 / 最大推力（設定 `maxThrust`=2000） |
| `setVector(x, y)` | ジンバル -1..1（各軸 ±`maxGimbal`=30°） |
| `getVector()` / `getTargetVector()` / `getMaxGimbal()` | 現在・目標のジンバル / 最大角 |
| `getAxes()` | `thrust`（押す方向）と `x`/`y`（ジンバル正方向）のワールド方向ベクトル。`local` は機体座標、`nozzle` は向きの名前 |
| `getThrustVector()` | 現在の力ベクトル（ワールド）、`local` は機体座標 |
| `stop()` | 出力0・ジンバル中央 |
| `setRedstoneControl(b)` / `isRedstoneControl()` / `getRedstoneInput()` | レッドストーン制御の切り替え・入力値 |
| `getSubLevel()` | 取り付けている機体のID（地上なら nil） |

ジンバル軸: ノズルが上下向きなら x=+X, y=+Z（ブロック座標）。横向きなら x=押す方向の右, y=上。
推力式: 方向 = normalize(thrust + tan(x·max)·axisX + tan(y·max)·axisY)。

## 反重力コア（`ashcc:antigravity_core`、周辺機器タイプ `antigravity`）

Sable の機体に載せると機体の重力を打ち消し、Lua の指令どおりに動かす。地上では何もしない（Sable が無いと置けるだけ）。

- 毎物理ステップ、重心に「質量×重力」の逆向きの力を加える（Sable の重力は物理エンジン全体で一律なので、機体ごとに消す方法はこれしかない）
- 制御の力は質量・慣性から計算するので、重さに関係なく同じ加速度で動く。上限は `setLimits`（既定 20 ブロック/秒・20 ブロック/秒²・45°/秒・90°/秒²）
- 既定は「無重力で静止を保つ」（速度 0・回転 0）。`release()` で制御をやめると重力だけ消えた状態で漂う
- 機体の向きは組み立てたときが基準（前 = 北、右 = 東）。角度の符号は `imu` と同じ
- 1機に複数あっても効くのは1つだけ。レッドストーン信号で停止（機体は落ちる）。動かしている間は光り、パーティクルが出る
- レシピ: 鉄インゴット・ファントムの皮膜・エンダーパール×2・ネザースター・レッドストーンブロック

| 関数 | 説明 |
|---|---|
| `setVelocity(x, y, z)` / `setLocalVelocity(right, up, forward)` | ワールド／機体基準の速度（ブロック/秒） |
| `moveTo(x, y, z)` / `move(dx, dy, dz)` | このブロックをその点へ運んで止まる |
| `setAngularVelocity(yawRate, pitchRate, rollRate)` | 度/秒で回す |
| `setRotation(yaw, [pitch], [roll])` / `level()` | その向きに回して保つ／水平に戻す |
| `stop()` / `hold()` / `release()` | 止まる／今の位置と向きを保つ／制御をやめる（`releaseLinear()` `releaseAngular()` で片方だけ） |
| `isAtTarget()` | moveTo・setRotation の目標に着いたか |
| `setGravity(s)` / `getGravity()` | 感じる重力の倍率（0 = 無重力、1 = 普通） |
| `setEnabled(b)` / `isEnabled()` / `isActive()` | 入/切、いま動かしているか |
| `setLimits{...}` / `getLimits()` | speed・acceleration・angularSpeed・angularAcceleration |
| `getPosition()` / `getVelocity()` / `getRotation()` / `getAngularVelocity()` / `getMass()` / `getSubLevel()` / `getState()` | 状態 |

イベント `antigravity_arrived`(name, `"position"`|`"rotation"`, x|yaw, y|pitch, z|roll)。例 `lua/antigrav_pilot.lua`（`fly` でキーボード操縦）。試験 `lua/antigrav_test.lua`（API・浮上・速度・移動・旋回・重力・電源の自動試験 40 項目、結果は `antigrav_test.log`。`--no-fall` で機体を落とす項目を省く）。

## オフセットコネクター（`ashcc:offset_connector`、アイテム＋エンティティ）

Sable の機体の上に、ブロックを格子から 1/2 ブロックずらして置くための板。ブロックではなく、面に貼り付くエンティティ（厚さ 1/16 の板）。

- **貼り方**: アイテムでブロックの面を右クリックすると、その面を 3×3 の 9 マスに分けたうち狙ったマスの方へ、面に沿って −1/2・0・+1/2 ブロックずれた位置に板が貼り付く（例: 右上のマスなら右上へ 1/2 ずつずれ、載せたブロックの中心が面の角に来る。端のマスなら中心が面の辺に来る。真ん中のマスはずれなし）
- **1つの面に何枚でも**: マスごとに1枚ずつ貼れる（同じマスに2枚目は貼れない）。隣り合うマスの板に載せたブロックは重なることがあるが、同じ機体に溶接された板どうしのブロックは互いに溶接され、ぶつかり合わない
- **ブロックを載せる**: 板をブロックで右クリックすると、そのブロックが板の位置（ずれた位置）に置かれる。そのブロックは自分だけの小さな Sub-Level になり、板を貼ったブロックの機体（地面に貼ったときはワールド）へ固定ジョイントで溶接される。続きはそのブロックに普通に置けば同じずれた格子に並ぶ。板は最初のブロックの下に隠れる
- **角度の調整**: レンチ（Create のレンチ、または `c:tools/wrench` タグの道具）で板、または板に載せたブロックを右クリックすると画面が開き、ヨー・ピッチ・ロールを 5° 刻み（−180〜180°）で変えられる。回転の中心は板の中心。「リセット」で 0° に戻る。ブロックを載せる前に角度を決めておいてもよい
- 親の機体と一緒に動き、親との間の衝突は無効。ジョイントは保存されないため、読み込むたびに板が自動で付け直す（載せたブロックを増やして重心が動いたときも）
- **外れ方**: 板に載せた最初のブロックを壊すと溶接が外れ（残りのブロックは別の機体になる）、板は何も載っていない状態に戻る。板を貼ったブロックを壊すと溶接が外れて板がアイテムで落ちる。板を殴っても外れてアイテムに戻る
- 親の機体を組み直す（分解・再組立）と溶接は外れる
- Sable が無いときは、板にブロックを載せると普通に格子どおり置かれ、板はアイテムに戻る
- レシピ: 鉄インゴット×4＋スライムボール（十字） → 2個

## コントロールパネル（`ashcc:control_panel`、周辺機器タイプ `control_panel`＋`hd_monitor`、Drive By Wire 対応）

1ブロックの板に、ボタン・トグル・ノブ・スライダー・サイドスティック・スロットル・画面（MFD）・HUD を自由に並べる操作盤。床・壁・天井に貼れ、Sable の機体上でも使える。並べて F-16 のようなコックピットを組める（`-PpanelShot` の見本）。

- **設定**: **スニーク＋素手で右クリック**したマスの設定画面で、部品の種類・幅・高さ・最初のチャンネル（1〜64）・色、パネル全体の**マス（4×4 / 8×8）**と**傾き（0〜60°、15° 刻み）**を決める。既にある部品のどのマスを開いてもその部品を編集する。重なる大きさは置けない。種類「なし」で外す。マスを 4↔8 に変えると部品は倍／半分に拡縮される。設定画面の右側にはパネル全体図があり、マスをクリックすると画面を閉じずにそのマスの編集に移れる。種類・画面の表示モード・色はボタンとパレットから直接選ぶ
- **傾き**: 板の手前の辺を軸に奥が持ち上がる（サイドコンソール向き）。クリックは視線と傾いた面の交点で判定する
- **つなぎ目**: 同じ向き・傾き・マスのパネルが隣り合うと、境目の縁が消えて1枚の計器盤に見える
- **操作**（素手で右クリック）:

| 部品 | 操作 | 出力（0〜15） |
|---|---|---|
| ボタン | 押している間 | 15 |
| トグルスイッチ | 右クリックで ON/OFF | 15 / 0 |
| ノブ | つかんで W/S で 16 段階 | 0〜15（離しても残る） |
| スライダー | つかんで W/S（全行程 2 秒） | 0〜15（離しても残る） |
| スロットル | つかんで R/F（単独なら W/S も）、C/V が親指ボタン | 3 ch: スロットル・親指1・親指2 |
| スティック | つかんで W/S/A/D、Space 引金、矢印キー ハット、X ボタン2。離すと中央に戻る | 10 ch: 前・後・左・右・引金・ハット上・下・左・右・ボタン2 |
| 画面（MFD） | 表示のみ（パネルのキャンバスの自分の範囲） | なし |
| HUD | 部品の手前の辺から立つ半透明ガラスにキャンバスを表示 | なし |

- **HOTAS**: スティックとスロットル（またはスライダー・ノブ）を**同時に**つかめる。両方持っている間はスティックが W/S/A/D・Space・矢印・X、スロットルが R/F・C/V。移動キーは体を動かさない。スニークで全部離す。キーは設定画面の「AshCC コントロールパネル」で変えられる
- **画面と HUD**: パネルごとに 256×256 のキャンバスがあり、CC から **HD モニターと同じ関数**（`fillRect`・`drawLine`・`drawText`・`drawImage`・`setAutoPresent` など）で描く。画面・HUD 部品は自分のマスに当たる部分を映す（8×8 なら 1 マス 32 px）。範囲は `getControls()` の `px, py, pw, ph`。HUD は透明色（`"transparent"`）で塗ってから明るい色で描くと景色に重なる
- **内蔵計器（CC 不要）**: 画面部品の設定で表示を「内蔵計器」に変えると、パネル自身が飛行計器を描く。人工水平儀（ピッチ・ロール）＋方位・速度・高度・昇降率。値はパネルが向いている向き（座ったパイロットから見た前方向）を基準に、Sable の機体上では自機の動きに追従し、地上では静止した値になる。コンピューターを繋がなくてもコックピットの最低限の計器が揃う。`getControls()` の `mode` が `"instruments"` の画面が対象
- **内蔵レーダー（Bスコープ、CC 不要）**: 画面部品の表示を「内蔵レーダー」に変えると B スコープ（横軸＝方位 ±75°、縦軸＝距離）を描く。範囲はレーダーの設定値（既定 2048）、走査は約1秒ごと。ドットの色は機体＝水色、プレイヤー＝黄、その他エンティティ＝緑。自分の機体に乗っているエンティティは表示しない。`mode` は `"radar"`
- **内蔵・機体上面図（CC 不要）**: 表示を「機体上面図」に変えると、載っている Sable 機体を真上から見た形を**緑の線の輪郭**で描く（画面に収まるよう自動で縮尺、約2秒ごとに再走査）。前が画面の上、パネルの位置は黄色い三角、重心は赤い十字、武装のある場所は赤ドット。機体に載っていない時は NO SHIP 表示。`mode` は `"ship"`
- **内蔵・武装ステータス（CC 不要）**: 表示を「武装ステータス」に変えると、同じ機体上の武装（地上では周囲1チャンク）の一覧と状態を表示専用で描く。レーザー（発熱％・過熱・発射中）、デコイ（フレア/チャフ残弾・クールダウン）、CBC 大砲マウント（装着中の大砲の種類）。発射操作はこの画面からはできない。`mode` は `"weapons"`
- **出力**:
  - **Drive By Wire**: パネルは複数チャンネルのケーブル源（`panel_1`〜`panel_64`、ケーブルを持ってホイールで選ぶ）。`world` はパネルのレッドストーン出力
  - **レッドストーン**: 全チャンネルの最大値
  - **CC**: `getChannel(n)`、`getChannels()`、`getControls()`（種類・位置・大きさ・チャンネル・色・状態 on/value/x/y/buttons/held、画面の範囲）、`getLayout()`（マス, 傾き）。イベント `control_panel`(name, チャンネル, 値)
- 設定 `controlPanel.stickRate`（既定 4/秒）、`sliderRate`（スライダー・スロットルの速さ、既定 0.5/秒）
- レシピ: 石のボタン×2・レバー・鉄インゴット×3

## コックピット（`ashcc:cockpit`）と座席（`ashcc:seat`）

戦闘機のコックピットを **1 ブロックで完結**させるブロック。中身はコントロールパネルそのもので、置いた瞬間に完成したレイアウト（8×8・傾き 30°）が乗っている。モデルには左右のサイドコンソールと座席が付く（床置き＝コンソール型が前提の見た目）。

- **初期レイアウト**: 上段にノブ（ch15）、MFD×2（左＝内蔵計器、右＝内蔵レーダー）、ボタン×2。中段にスロットル（ch1〜3）、スティック（ch4〜13）、スライダー（ch14）、ボタン×4、トグル×2。下段にボタンとトグル、ノブ（ch28）。画面をクリックすると表示ページが キャンバス→計器→レーダー→機体→武装 と切り替わる（通常のパネルでも同じ）。レイアウトは普通のパネルとしてスニーク＋右クリックで自由に組み替えられる
- **着座**: 素手で右クリックすると座る。座っている間はスティックとスロットルを自動で掴んだ状態になり、HOTAS のキー（W/S/A/D・Space・R/F など）でそのまま操縦できる。ボタン類は狙って右クリック。降りるのはスニーク
- **座席（`ashcc:seat`）**: 単体の椅子ブロック。素手で右クリックで座る、スニークで立つ。置いた向きに座る。通常のコントロールパネルにも座れる（壁付けは正面、床置きは手前側）
- 座席は船（Sable のサブレベル上）でもそのまま使える。座ったまま船ごと移動する

## リンクハブ（`ashcc:link_hub`）とリンクカード（`ashcc:link_card`）

ケーブル無しで離れた周辺機器をコンピューターに繋ぐ。有線モデムと同じ仕組み（`peripheral_hub`）なので、`peripheral.getNames()` / `peripheral.wrap("radar_0")` / `peripheral.find("thruster")` がそのまま使え、`peripheral` / `peripheral_detach` イベントやレーダーのイベントも届く。

使い方:
1. リンクハブをコンピューターの隣に置く
2. リンクカードで周辺機器を右クリック → カードに追加（もう一度で外す）。複数まとめて集められる
3. カードでリンクハブを右クリック → カードの周辺機器をまとめて登録（カードは空になる）
   - スニーク＋右クリックでハブから登録解除。空中でスニーク＋右クリックでカードを空にする
   - 素手でハブを右クリックするとリンク一覧をチャットに表示
4. 名前は有線モデムと同じく `型_番号`（`radar_0`, `thruster_1` …）

- 周辺機器のチャンクが読み込まれていない間は切断扱い（`peripheral_detach`）、読み込まれると自動で再接続（10 tick ごとに確認）。ブロックを壊しても登録は残る
- Sable の機体上のブロックはプロット座標で登録されるので、機体が動いても繋がったまま
- 既定で距離無制限・他ディメンション可（設定で変更可）。ハブ同士は繋がない

ハブ自身の関数（`peripheral.find("link_hub")`）:
| 関数 | 説明 |
|---|---|
| `getLinks()` | `{ {name, type, dimension, x, y, z, side, connected}, ... }` |
| `removeLink(name)` | 登録解除 |
| `renameLink(name, newName)` | 名前を変える（例 `main_engine`） |

## ヘッドトラッカー（`ashcc:head_tracker`、周辺機器タイプ `head_tracker`）

プレイヤーの頭（カメラ）の向きを、リンクハブ経由でコンピューターに取り込むアイテム。登録後は持ち歩かなくてよい。

使い方:
1. ヘッドトラッカーを空中で右クリック → 自分に紐付け（スニーク＋右クリックで解除）
2. ヘッドトラッカーでリンクハブを右クリック → `head_tracker_0` として登録（スニーク＋右クリックで登録解除）
3. プレイヤーがオンラインでリンク範囲内の間だけ接続。ログアウトすると `peripheral_detach`、戻ると再接続

角度はレーザーと同じ規約: yaw は北=0・東が正（-180..180）、pitch は上が正。`local` はハブの座標系（ハブが機体上なら機体の北・上）なので、同じ機体のレーザーの `setAim` にそのまま渡せる。

| 関数 | 説明 |
|---|---|
| `getPlayer()` | プレイヤー名, UUID |
| `isOnline()` | オンラインか |
| `getYaw()` / `getPitch()` / `getAngles()` | ワールド基準の yaw / pitch |
| `getLocalAngles()` | ハブ（機体）基準の yaw, pitch |
| `getLook()` | `{yaw, pitch, localYaw, localPitch, direction, localDirection, eye, dimension, name, [subLevel]}` |
| `getLookTarget([range])` | 視線の先（既定 128、最大 1024）。`laser.trace()` と同じ形 `{type, x, y, z, distance, ...}` |

```lua
local tracker = peripheral.find("head_tracker")
local laser = peripheral.find("laser")
while true do
  laser.setAim(tracker.getLocalAngles())
  sleep(0.05)
end
```

例: `lua/head_aim.lua`（全レーザーが視線に追従。`head_aim point` で視線の先を狙う。背面にレッドストーンで発射）。

レシピ: 鉄インゴット×2・エンダーアイ・板ガラス・レッドストーン（上: 板ガラス、中: 鉄・エンダーアイ・鉄、下: レッドストーン）

## コンピューター内蔵の IMU（グローバル API `imu`）

AshCC を入れると、**すべてのコンピューター・タートル・ポケットコンピューターにグローバル `imu` が入る**。ブロックも CC: Sable も要らず、コンピューター自身の姿勢・角速度・速度・加速度を取得できる。Sable の機体に置けば機体と一緒に動き、地上では「水平で静止・1 g 上向き」を返す。

```lua
local a = imu.getAngles()          -- そのまま使える（peripheral.find は不要）
print(a.yaw, a.pitch, a.roll)
imu.setStream(1)                   -- 毎 tick イベント "imu"("computer", 表)
local _, _, t = os.pullEvent("imu")
```

- 「前」はコンピューターの正面、タートルなら向いている方向、ポケットコンピューターなら持っている人が水平に向いている方向（ポケットは上下の傾きを持たない）。`local` は x=右, y=上, z=前（レーダーと同じ）
- 角度は度、角速度は度/秒、速度はブロック/秒、加速度はブロック/秒²
- 使っている間だけ毎 tick 測る（最後の呼び出しから 5 秒、または `setStream` 中）。関数は最新の値をすぐ返す（main thread 待ちなし）。止まっていた後の最初の呼び出しは 1 tick 待ち、速度・角速度は次の tick から出る
- 速度・加速度はコンピューターのワールド位置の差分（機体の回転による動きも含む）。角速度は連続する姿勢から求める（dq = conj(q前)·q）ので、姿勢の変化と必ず一致する
- タートルの移動は 1 ブロックずつ瞬間的に動くので、速度・加速度は移動の瞬間に跳ねる

| 関数 | 説明 |
|---|---|
| `getAll()` | 下の全項目を1つの表で |
| `getAngles()` | `{ yaw, pitch, roll }` — 方位（北=0、東=+90）、機首上げ +、右下がり + |
| `getAngularVelocity()` | ローカル角速度 `{x,y,z}` ＋ `yawRate`（上から見て時計回り +）, `pitchRate`（機首上げ +）, `rollRate`（右下がり +） |
| `getVelocity()` / `getLocalVelocity()` | 速度（ワールド / ローカル） |
| `getAcceleration()` | 加速度（ワールド、静止で 0） |
| `getGForce()` | 加速度計の読み（ローカル、g 単位。静止して水平なら y=1、自由落下で 0） |
| `getOrientation()` | 機体の姿勢の四元数 `{w,x,y,z}`（地上は単位四元数） |
| `getAxes()` | ローカル軸 `{ right, up, forward }` のワールド方向 |
| `getPosition()` / `isOnShip()` | ワールド座標 / 機体上か |
| `getGravity()` | 加速度計が使う重力（設定 `imu.gravity`、既定 9.8） |
| `setStream(ticks)` / `getStream()` | `ticks` ごとにイベント `imu`("computer", 表) を送る。0 で止める |

`getAll()` の表: `position, velocity, localVelocity, speed, acceleration, localAcceleration, gForce, yaw, pitch, roll, orientation, axes, angularVelocity, localAngularVelocity, yawRate, pitchRate, rollRate, onShip, subLevel, tick`

- `lua/imu_view.lua` — 計器表示（方位・ピッチ・ロール・角速度・速度・昇降率・荷重）。イベントで更新

## 高精細モニター（`ashcc:hd_monitor`、周辺機器タイプ `hd_monitor` と `monitor`）

CC のモニターより細かく自由に描けるモニター。

- **1ブロック 128×128 ピクセル、フルカラー（32bit・半透明）**。同じ向きで並べると長方形につながる（既定で最大 16×12 ブロック = 2048×1536 px。CC は 8×6）
- **壁・床・天井**のどこにでも置ける（床・天井は置いたときの向きが画面の上）
- **描画 API**: 点・長方形・線（太さ・小数座標）・円・楕円・多角形・文字（Unifont、日本語を含むほぼ全 Unicode、整数倍に拡大）・画像（RGBA / RGB / パレット）・領域コピー・クリップ・半透明合成・アンチエイリアス
- **term 互換**: CC のモニターと同じ関数（`write`, `blit`, `setCursorPos`, `setTextScale` …）を持つので `term.redirect(mon)` で既存のプログラムがそのまま動く。文字は同じピクセルに描かれ、図形と混ぜられる
- **ちらつき無し**: `setAutoPresent(false)` で描画を溜め、`present()` でまとめて表示
- **ホログラム**: `setHologram(true)` で外枠が消え、透明なピクセルの向こうが見える。`setGlow(false)` で周りの明るさに合わせる
- **タッチ**: 右クリック＝ボタン1、スニーク＋右クリック＝ボタン2。押したまま視線を動かすとドラッグ、ホイールでスクロール。座標はピクセル
- **Sable**: 機体に載せても表示・タッチが動く

| イベント | 引数 |
|---|---|
| `hd_touch` / `hd_drag` / `hd_release` | 名前, x, y, ボタン(1/2), プレイヤー名 |
| `hd_scroll` | 名前, x, y, 向き(上 -1 / 下 1), プレイヤー名 |
| `monitor_touch` | 名前, 列, 行（CC と同じ） |
| `monitor_resize` | 名前（文字の大きさを変えたとき） |

色は `0xRRGGBB`（不透明）、`0xAARRGGBB`、`"#RRGGBBAA"`、`"transparent"`、`{r,g,b,a}`。線と円の座標はピクセルの中心、`fillPolygon` の点はピクセルの角。関数の一覧は `docs/index.html`。

```lua
local mon = peripheral.find("hd_monitor")
local w, h = mon.getResolution()
mon.setAutoPresent(false)
mon.fill(0x101820)
mon.fillCircle(w / 2, h / 2, 60, "#40c0ff80")
mon.drawText(w / 2, 10, "こんにちは HD", 0xffffff, { scale = 2, align = "center" })
mon.present()
```

- 例: `lua/hd_demo.lua`（`radar` レーダーのスコープ、`paint` タッチでお絵描き、`term` シェルをモニターに出す）
- 仕組み: 描画命令だけを送り、サーバーとクライアントが同じ計算で描く（通信量が小さい）。範囲（`syncRange`）に入ったプレイヤーには画面全体を圧縮して送る
- 制限: 画面の中身はブロックに保存しない（CC と同じ）。`setPaletteColor` はこれから描く文字にだけ効く
- フォント: GNU Unifont 15.1.05（SIL OFL 1.1、`com/ashcc/monitor/UNIFONT_LICENSE.txt`）

## IFFトランスポンダー（`ashcc:transponder`、周辺機器タイプ `transponder`）

機体に積むと、レーダー・シーカーがその機体を返すときに `iff = { code = "...", callsign = "..." }` が付く。敵味方の判定はプログラム側で行う（コードは誰でも同じものを設定できるので、なりすましもできる）。

| 関数 | 説明 |
|---|---|
| `getCode()` / `setCode(code)` | IFFコード（最大32文字、前後の空白は除く）。`""` で送信しない |
| `getCallsign()` / `setCallsign(name)` | コールサイン（最大32文字） |
| `isEnabled()` / `setEnabled(bool)` | 送信の入/切 |
| `isTransmitting()` | いまレーダーに見えているか（有効・コードあり・レッドストーン信号なし・機体上の4条件） |
| `isSilenced()` | レッドストーン信号で止まっているか |
| `getSubLevel()` | 識別している機体のID（機体上でなければ `nil`） |

- 名前を付けた名札を使うとコードを設定できる。素手で使うと状態を表示
- レッドストーン信号を入れている間は送信しない（電波封止）
- 1機に複数あるときは、ブロック座標が最も小さいものが使われる
- 設定は機体の組み立て後も残る

## フレア／チャフ発射機（`ashcc:decoy_launcher`、周辺機器タイプ `decoy_launcher`）

囮を撃ち出す。**チャフはレーダーに機体として映り、フレアはシーカーに機体として映る**（もう一方のセンサーには映らない）。どちらもエンティティの探知・レーザーには掛からない。

| 関数 | 説明 |
|---|---|
| `fire(kind, [count])` | `kind` は `"flare"` / `"chaff"`。同時に `count` 発（既定 1、上限 `maxSalvo`）。戻り値は撃った数。撃てなければ `0, "cooldown"` / `0, "empty"` |
| `getAmmo()` | `{ flare = n, chaff = n }` |
| `getCapacity()` / `getMaxSalvo()` | 1種類あたりの装填数の上限 / 1回に撃てる数 |
| `getCooldown()` / `isReady()` | 次に撃てるまでの秒数 / 撃てるか |
| `getFacing()` | 撃ち出す向き（ブロックの向き。機体上なら機体と一緒に回る） |

- 設置時にプレイヤーが見ていた方向（上下も可）に撃ち出す。発射速度に機体の速度が足される
- 弾はアイテム `ashcc:flare` / `ashcc:chaff`。発射機に使うか、ホッパーなどで入れる（取り出しはできない。プレイヤーが壊すと中身を落とす）
- チャフのエコーは撃った機体の `size`・`mass`・`orientation` を写し、`iff` は無い。すぐに減速して漂う（既定 8 秒）
- フレアは落ちながら燃える（既定 6 秒）
- 見破り方の例: 速度がすぐ落ちる／レーダーで `getContact(フレアのID)` が `nil`（レーダーには映らない）／チャフはシーカーに映らない
- `lua/countermeasures.lua` — 近づいてくる非友軍機があると、繋いだ全発射機からチャフとフレアを撒く（f/c で手動、a で自動の入/切）。自分のチャフ（自機と同じ質量・大きさ）は無視する

## フラップベアリング（`ashcc:flap_bearing`、周辺機器タイプ `flap_bearing`、Create が必要）

CC で角度を指令するサーボ式のベアリング。前面に付けたブロック（帆・翼など）をコントラプションとして回し、指定角度で止める。回転動力は不要（シャフトにも繋がらない）。Sable の機体に載せれば、帆を付けて舵面（フラップ・エルロン・ラダー）として使える。

- 組み立て: 素手で右クリック（メカニカルベアリングと同じ）か Lua の `assemble()`
- 分解: もう一度右クリックか `disassemble()`。0° でないときは 0° に戻ってから分解する（ブロックが斜めに置かれないように）

| 関数 | 説明 |
|---|---|
| `setAngle(deg)` | 目標角度（±`maxAngle`=180 に制限、範囲外は -180..180 に折り返し）。戻り値は実際の目標 |
| `getAngle()` / `getTargetAngle()` / `isAtTarget()` | 現在角・目標角・到達したか |
| `setSpeed(degPerSec)` / `getSpeed()` | 回転速度（既定 90°/秒、上限 `maxSpeed`=720°/秒） |
| `assemble()` | 組み立て。失敗すると `false, 理由` |
| `disassemble()` | 分解。`false` は「0° に戻ってから分解する」 |
| `isAssembled()` | 組み立て済みか |
| `getAxis()` | 回転軸（ベアリングの向き）のワールド方向。機体と一緒に回る |
| `getMaxAngle()` / `getMaxSpeed()` | 設定値 |

```lua
local flap = peripheral.find("flap_bearing")
flap.assemble()
flap.setSpeed(180)
flap.setAngle(15)   -- 15° 下げる
```

## レッドストーンフラップベアリング（`ashcc:redstone_flap_bearing`、Create が必要、コンピューター不要）

フラップベアリングをレッドストーンだけで動かす版。周辺機器ではない。前面に付けたブロックを組み立て、入ってくる信号の強さに比例した角度まで回して止める。

- 組み立て／分解は素手で右クリック（分解は 0° に戻ってから）
- 角度 = （側面4面のうち一番強い信号 − 背面の信号）÷ 15 × `redstoneAngle`（既定 45°、信号1あたり 3°）。側面に入れると＋、背面に入れると−、信号なしで 0°
- 回る速さは `flapBearing.defaultSpeed`（既定 90°/秒）
- コンパレーターで振れ幅を読める（0° で 0、`redstoneAngle` で 15。向きは区別しない）
- レッドストーンダストは前面以外の5面につながる
- レシピ（不定形）: メカニカルベアリング＋コンパレーター＋レッドストーン

## 4方向フラップベアリング（`ashcc:quad_flap_bearing`、周辺機器タイプ `quad_flap_bearing`、Create が必要）

1ブロックで4枚のフラップを別々に動かす（ミサイルの十字尾翼など）。ブロックの向き（FACING、設置時に見ていた方向）が胴体の軸で、その周りの4面それぞれに付けたブロックがフラップになり、その面の軸まわりに回る。動力不要。

- フラップの名前は付いている面の方角（ブロック座標）: 上向きに置けば `north, south, west, east`。機体に載せてもこの名前のまま
- 正の角度 = その面の正の軸まわりの右ねじ回転。向かい合う2枚に**同じ角度** → 同じ向きに傾く（ピッチ・ヨー）、**逆の角度** → 胴体をねじる（ロール）
- フラップの面を素手で右クリックで、その1枚を組み立て／分解。ブロックを壊すと全部ブロックに戻る
- フラップ同士や胴体のブロックと接着剤（Super Glue）でつながないこと（その面の組み立てに巻き込まれる）

| 関数 | 説明 |
|---|---|
| `getSides()` | 4つの面の名前 |
| `assemble([side])` | 1枚なら `true` / `false, 理由`。省略すると全部で `組み立てた数, {面 = 理由}` |
| `disassemble([side])` | 0° に戻ってから分解。すぐ終わったら `true` |
| `isAssembled([side])` | 1枚なら bool、省略で `{面 = bool}` |
| `setAngle(side, deg)` | 目標角度。`side` に `"all"` で4枚とも |
| `setAngles({north = 10, south = -10, ...})` | まとめて指定（同じ tick に反映） |
| `getAngle([side])` / `getTargetAngle([side])` | 1枚なら数値、省略で `{面 = 角度}` |
| `isAtTarget([side])` | 省略で全部が到達したか |
| `setSpeed(degPerSec)` / `getSpeed()` | 4枚共通の速度（既定 90°/秒） |
| `getFacing()` / `getAxis(side)` | 胴体の軸・各フラップの回転軸のワールド方向（機体と一緒に回る） |

```lua
local fins = peripheral.find("quad_flap_bearing")
fins.assemble()
fins.setAngles({ north = 10, south = 10 })   -- 片方の対を同じ向きに
fins.setAngles({ west = 5, east = -5 })      -- もう一方の対を逆向き（ロール）
```

テストプログラム `lua/quad_flap_test.lua`:
- `quad_flap_test [名前] [--keep]` — 4枚の組み立て、別々の角度に同時に到達、`"all"`、1枚だけ動かして他が動かないこと、胴体軸の面を拒否、分解 を確認（`quad_flap_test.log`）
- `quad_flap_test manual` — Tab でフラップ選択、←→ 角度、↑↓ 速度、a/d で全部を組み立て／分解
- `quad_flap_test wave pair1|pair2|roll|each [振幅] [周期]` — 片方の対だけ・もう一方の対だけ・ロール・1枚ずつ順番 に振る

## サーボベアリング（`ashcc:servo_bearing`、周辺機器タイプ `servo_bearing`、Create Aeronautics（Simulated）が必要）

Simulated のスイブルベアリングを CC 制御のサーボにしたもの。組み立てると前面のブロックが**別の Sable 機体（ターンテーブル）**になり、回転ジョイントでベアリングにつながる。コントラプションではないので、**組み立てた後もターンテーブル上にブロックを置いたり壊したりできる**。回転動力は不要で、ジョイントのモーターを Lua で指令した角度へ一定速度で回す。

- 組み立て: 素手で右クリック（ベアリング本体かターンテーブル）か `assemble()`。前に何も無ければターンテーブルだけの機体ができ、その上に建てられる
- 組み立ての範囲はスイブルベアリングと同じ規則（Simulated の組み立て処理をそのまま使う）。隣のブロックは接着剤（Super Glue / Honey Glue）でつながっていないと巻き込まれない
- 分解: もう一度右クリックか `disassemble()`。0° に戻ってから（5秒で戻れなければそのまま）ブロックをワールドへ戻す。ロック解除中は即分解
- 本体を壊すとターンテーブルの板が消え、板を壊すと本体も壊れる（どちらもベアリング1個を落とす）
- 機体に載せても動く（ジョイントは機体とターンテーブルの間に張られる）

| 関数 | 説明 |
|---|---|
| `setAngle(deg)` | 目標角度（-180..180 に折り返し、近い向きに回る）。戻り値は実際の目標 |
| `getAngle()` | ターンテーブルの実測角度（-180..180、組み立て時が 0、向きに対して右ねじが正） |
| `getTargetAngle()` / `isAtTarget()` | 目標角・指令が終わって実測が 1° 以内か |
| `setSpeed(degPerSec)` / `getSpeed()` / `getMaxSpeed()` | 回転速度（既定 90°/秒、上限 `maxSpeed`=720°/秒） |
| `setLocked(bool)` / `isLocked()` | `false` でジョイントを自由回転（摩擦あり）にする。`true` に戻すと今の角度で止め、目標へ回り直す |
| `assemble()` | 組み立て。失敗すると `false, 理由` |
| `disassemble()` | 分解。`false` は「0° に戻ってから分解する」 |
| `isAssembled()` | 組み立て済みか |
| `getSubLevel()` | ターンテーブルの機体の UUID（レーダーや CC: Sable と同じ ID）。未組み立てなら nil |
| `getAxis()` | 回転軸（ベアリングの向き）のワールド方向。機体と一緒に回る |

```lua
local servo = peripheral.find("servo_bearing")
servo.assemble()
servo.setSpeed(45)
servo.setAngle(90)
while not servo.isAtTarget() do sleep(0.1) end
```

テストプログラム `lua/servo_test.lua`（リンクハブ経由でも使える）:
- `servo_test [名前] [--keep]` — 組み立て、引数の検査（nan/inf の拒否・速度の上下限・角度の折り返し）、各角度への到達と所要時間・実測速度、150→-150 が 180° 側を回る（近回り）、2秒間の保持（ずれ 1° 未満）、ロック解除→再ロックで目標に戻る、最高速での移動、0° に戻って分解 を確認し、PASS/FAIL を `servo_test.log` に。ターンテーブルは物理で動くので、到達は 1.5° 以内、速度は ±35% を合格とする
- `servo_test manual [名前]` — ←→ 角度、↑↓ 速度、1-9 刻み、Space で 0°、l でロック切替、a/d で組み立て／分解、Tab で次のベアリング
- `servo_test wave [振幅] [周期]` — 全ベアリングを正弦波で振る（既定 ±45°・4秒）
- `servo_test spin [度/秒]` — 回し続ける（砲塔のように。負で逆回転、上限は `maxSpeed`）

## Create Modern Warfare（createarms）のミサイルを CC で使う（AshCC の追加ブロックではない）

createarms（Create Modern Warfare）が入っていると、そのブロックが CC の周辺機器になる（createarms は Sable が要る）。createarms 側は変更していない。

| ブロック | 周辺機器タイプ |
|---|---|
| ミサイルラック・パイロン全般（munition rack / dual / AIM-120 / sidewinder / AGM-114 / GBU-39 ラック、トラピーズ、スティンガー発射機） | `missile_rack` |
| VLS ハッチ（下に VLS セル6個） | `vls` |
| CIC 端末 | `cic_terminal` |
| レーダー（捜索レーダー・APG レーダー） | `missile_radar` |
| チャフ・フレア散布機 | `countermeasure_dispenser` |
| アイアンドーム・レーダー | `iron_dome` |

**目標（target）の指定**: `nil` / `{x=, y=, z=}` / `x, y, z` / 機体 ID か エンティティ ID の文字列（AshCC の `radar`・createarms の `missile_radar` の接触 ID）。機体 ID を渡すとミサイルはその機体を追う。

`missile_rack`:
| 関数 | 説明 |
|---|---|
| `getMissile()` | `{ name, displayName, count, capacity, firable }` か nil。`firable` は CC で撃てる弾か（ロケットポッドのハイドラや TV 誘導ミサイルは不可） |
| `getCount()` / `getCapacity()` / `getFacing()` | 弾数 / 装填上限（2連・4連ラックは 2・4） / 発射方向 |
| `fire([target])` | 1発撃つ。ミサイルの ID を返す。撃てなければ `nil, "empty"` / `"unsupported"` / `"blocked"`（ラックの下が塞がっている）。目標なしなら前方 500 ブロック（赤外線などは自分のシーカーで捕まえる） |

`vls`:
| 関数 | 説明 |
|---|---|
| `getMissile()` / `isReady()` / `isValidStructure()` | 中身 / 装填済みでセルが揃っているか / セル6個が揃っているか |
| `openHatch()` / `closeHatch()` | ハッチの開閉（`launch` も開ける。1秒前に開けておくと CIC の発射と同じ見た目） |
| `launch([target])` | 発射して ID を返す。`nil, "empty"` / `"no cell"` / `"blocked"` / `"unsupported"`。目標なしなら真上 500。LRASM・トマホークは座標で撃つ |

`cic_terminal`:
| 関数 | 説明 |
|---|---|
| `getLinkedVls()` | リンク済みセルの一覧 `{ index, x, y, z, loaded, ready, missile }` |
| `launch([target], [cells])` | 端末の発射キューに入れる（ハッチが開いて1秒後に発射、0.75秒間隔）。`cells` は nil=全部 / 番号 / 番号の表。入れた数を返す。目標なしなら端末の目標点。ID 目標のときは対艦ミサイルを飛ばさない（端末の画面と同じ） |
| `getQueueSize()` / `getActiveMissiles()` | キューの残り / 飛行中のミサイル ID（画面から撃ったものも） |
| `getTargetPos()` / `setTargetPos(x, y, z)` | 端末の目標点 |
| `getAutoEngage()` / `setVlsAutoEngage(b)` / `setCiwsAutoEngage(b)` | 自動交戦（VLS はパトリオットで敵ミサイルを迎撃） |
| `getContacts()` | 端末のレーダーの接触（無ければ `nil, "no radar"`） |

ラック・VLS・CIC 共通（飛んでいるミサイルの操作）:
| 関数 | 説明 |
|---|---|
| `getMissiles()` | 自分が撃ったミサイルの一覧（下の表） |
| `getMissileInfo(id)` | 1発の表。消えたら nil |
| `retarget(id, target)` | 目標を変える（中間誘導）。飛行時間・燃焼・信管のタイマーはリセットしない |
| `detonate(id)` | 自爆。魚雷は不可 |

- 操作できるのは **その周辺機器から CC で撃ったもの**だけ（CIC は自分のキュー・画面から撃ったものと、リンク済みセルから CC で撃ったものも）。他のラックのミサイルや敵のミサイルは見えない・動かせない
- ミサイルの表: `id, kind ("missile"/"torpedo"), name, active, x, y, z, vx, vy, vz, speed（ブロック/秒）, age / maxAge（秒）, guidance ("radar"/"ir"/"laser"/"gps"/...), launchType, warhead, iff, target {x,y,z}, targetId`
- IFF コードは近くの createarms レーダーのものを使う（createarms の発射と同じ）

`missile_radar`: `getContacts()`（`{ id, x, y, z, distance, bearing, type, friendly }` の一覧）、`getSelectedTarget()` / `selectTarget(id か nil)`（MFD のレーダー誘導発射が狙う目標。味方は選べない）、`getIffCode()` / `setIffCode(n)`、`isRadiating()`（レッドストーンで停止中は false）、`getScanRange()`

`countermeasure_dispenser`: `deploy([mode])`（出せたら true。0.5 秒のクールダウン中・弾切れは false）、`getMode()` / `setMode("flare"|"chaff"|"both")`、`getCount()` / `getCapacity()`

`iron_dome`: `getSettings()`（`autoEngage, scanRadius, targetMissiles, targetPlayers, targetShips, iff, ammo`）、`getAmmo()`、`setAutoEngage(b)`、`setScanRadius(r)`、`setTargets(missiles, players, ships)`、`setIffCode(n)`

`lua/missile_control.lua`:
- `missile_control` — 発射機・接触・飛行中のミサイルの一覧
- `missile_control fire <ID か x y z> [数]` — 装填済みのラック・VLS から撃つ（ID は先頭の数文字でもよい）
- `missile_control cic <目標> [1,3]` — CIC 端末から撃つ
- `missile_control watch` — 飛行中のミサイルを表示（d で全弾自爆）
- `missile_control guide <ID>` — 1発撃ち、AshCC のレーダーの `intercept` で見越し点へ 0.25 秒ごとに誘導し直す

## Create Big Cannons の砲を CC で撃つ（周辺機器タイプ `cannon_mount`、Create Big Cannons が必要）

CBC が入っていると、CBC の砲架（`createbigcannons:cannon_mount`）と固定砲架（`fixed_cannon_mount`）が周辺機器 `cannon_mount` になる。載っている砲は CBC の大砲・迫撃砲・オートキャノンと、AshCC のロータリー機関砲・レールガン・レーザー砲。

- 角度はレーザーと同じ: yaw（北=0、東が正、-180〜180）、pitch（上が正）。機体の上では機体の北・上
- 旋回は回転力なしで `aimSpeed`（45°/秒、`setAimSpeed` で最大 180°/秒）。俯仰の範囲は砲架と砲の種類で決まる。固定砲架は回せない（発射・組み立て・情報だけ）
- `aimAt(x, y, z, [{velocity={x,y,z}, high=true, speed=...}])` は、込めてある弾の重力と空気抵抗を CBC と同じ式（毎 tick `pos += v + a/2`、`v += a`）で計算して狙う。目標の速度（ブロック/秒）を渡すと見越す。戻り値 `ok, yaw, pitch, 飛行秒数`
- 大砲・迫撃砲・レールガンは `fire()` で1発、オートキャノンは `setFiring(true, [rate])` で撃ち続ける（CBC はブロック更新のたびに発射側のレッドストーンで発射速度を上書きするので、Lua の引き金は毎 tick かけ直している）
- `assemble()` はレッドストーンでの組み立てと同じ処理。CC で組み立てたあとレッドストーンが変わっても分解されない
- その他: `getCannon()`（種類・角度・範囲・回転・電力）、`getAim()` / `getTargetAim()` / `isAimed()` / `releaseAim()`、`getMuzzle()`（回転中心・砲身方向・弾の出る位置）、`getBallistics()`（初速・重力・抗力・寿命）、`getAmmo()`、`disassemble()`、`getSubLevel()`
- 詳細は `docs/index.html` の「Create Big Cannons 連携」

### ロータリー機関砲（`ashcc:rotary_autocannon_breech` / `ashcc:rotary_autocannon_barrel`）

- CBC のオートキャノンと同じ組み方（尾栓の前に砲身を最大4本、尾栓は砲口の方を向ける）。置くと隣とつながる。CBC の弾・機関銃弾・弾倉がそのまま使え、ベルト給弾で 32 発まで込められる
- 引き金を引くと 1.5 秒で回転が上がり、回転 25% から撃ち始める。最大で毎分 1200 発（1 tick に1発、設定で 2400 まで）。離すと 2.5 秒で止まる
- 素材 `ashcc:rotary`: 初速 4 + 砲身ごとに 1.5（3本まで）ブロック/tick、弾の寿命 2 秒。`data/ashcc/autocannon_materials/rotary.json` で変更できる

### レールガン（`ashcc:railgun_breech` / `ashcc:railgun_rail`）

- CBC の砲架に載せる新しい砲の種類（CBC の砲タイプ `ashcc:railgun`。俯仰の範囲は `data/createbigcannons/cannon_mounts/` の JSON）
- 尾栓1つの前にレールを一列（1〜15）。尾栓はレールの方を向ける。弾は CBC の1ブロックの砲弾（徹甲弾・榴弾など）を8発まで、手・ホッパー（尾栓か砲架）で入れる。スニーク＋素手で1発戻す。素手で使うと弾と電力を表示
- 1発 20000 FE。組み立て後は砲架に、組み立て前は尾栓に FE を入れる（容量 100000、毎 tick 5000 まで）
  - 電気は Create Crafts & Additions（インスタンスに入っている。テスト環境 `run-test`・`run` にも同期）で作る: オルタネーター（回転力 → FE）を砲架に隣接させるか、コネクターと電線で砲架につなぐ。クリエイティブなら Creative Generator を隣に置くだけで満タンになる（`-PcbcShot=power` で確認済み）
- 初速 = 12 + 3 ×（レール数−1）ブロック/tick（上限 60）。1 ブロック = 1 m なら音速は約 17 ブロック/tick なので、レール 3 本以上で超音速。火薬を使わず失敗しない。レッドストーンの立ち上がりか `fire()` で撃ち、2 秒の冷却
- 演出（クライアント側、512 ブロック以内のプレイヤー）:
  - 砲口: 閃光、砲身と直角に広がる衝撃波の輪3枚、火花、水蒸気の輪。発射音と画面の揺れは CBC の衝撃波（音速で広がり、遠いほど遅れて届く）
  - 弾道: 青白く光る弾道痕（芯は先に消え、外側は広がりながら 2.5 秒で薄れる）。超音速なら 8 ブロックごとにマッハリング
  - ソニックブーム: 超音速の弾が近く（96 ブロック以内）を通り過ぎると、通過点から破裂音とヒュッという風切り音、画面の揺れが音速で遅れて届く。砲の後ろにいると聞こえない（発射音だけ）
  - 低空通過: 7 ブロック以下の高さを超音速で飛ぶと、衝撃波で地面の土埃（水面ならしぶき）が両側に舞う
  - 着弾（弾が消えたところ）: 閃光、衝撃波の輪、火花
- 設定は `serverconfig/ashcc-server.toml` の `[cannonMount]` `[rotaryAutocannon]` `[railgun]`

### レーザー砲（`ashcc:laser_cannon_emitter` / `ashcc:laser_cannon_lens`）

- CBC の砲架に載せる砲の種類（CBC の砲タイプ `ashcc:laser_cannon`、上下 90°/30° まで向けられる）。発振器1つの前にレンズを一列（1〜8）。発振器はレンズの方を向ける
- 引き金（砲架の発射側のレッドストーン、または `setFiring(true)`）を引いている間、砲身の向きに光線を出し続ける。動きは AshCC のレーザーと同じ: ブロックを硬さに応じて焼き切り、エンティティにダメージと着火、Sable の機体を押し、自機には反動（自機のブロックは既定で壊さない）
- レンズが多いほど強い（既定）: 射程 96 + 48/枚（レンズ4枚で 240）、焼き切り 1.5 + 1.0/枚（ハードネス/tick。AshCC のレーザーは 0.5）、ダメージ 6 + 2/枚、着火 5 秒、機体を押す力 300
- 照射中は毎 tick 400 FE（発振器の容量 200000、毎 tick 10000 まで受け取る。レールガンと同じく砲架か組み立て前の発振器に給電）。連続 10 秒で過熱し、5 秒で冷えるまで撃てない
- `fire()` は `false, "lasers fire with setFiring"`。`aimAt` は光線なので重力なしの直線で狙う。`getCannon()` に `energy, energyCapacity, energyPerTick, heat, overheated, firing, trigger, range, beamLength, lenses`
- 見た目: 太い青白い光線（外側のぼんやりした光・本体・白い芯）、砲口と当たった所の光、当たった所の火花・溶けた粒・煙。光線は砲架の旋回に合わせて滑らかに動く
- `cbc_turret.lua` はレーザー砲も扱う（照準が合っている間だけ引き金を引く）

### 自動砲塔 `lua/cbc_turret.lua`（レーダー＋全砲架）

- `cbc_turret [range] [--hold] [--mobs]`。レーダーの機体（`--mobs` で飛ぶモブも）から味方を除き、近い・近づいてくる順に、つながっている全砲架で狙う
- 味方: `turret_friends.txt`（`f` で一番の目標を登録、`c` で消去）、自機と同じ IFF コード、自分のチャフ。射線の近く（4°）で目標より手前に味方がいると撃たない
- `aimAt` で見越し、照準の誤差が 1° 以内になったら撃つ（オートキャノンはその間引き金を引く）。`space` で発射許可／保留、`q` で終了
- 動作ログ `cbc_turret.log`（捕捉・発射・発射できない理由・エラー）。テストクライアント（`-PcbcShot`）で、浮いている標的にレールガンが2発命中し、ロータリー機関砲が連射するのを確認済み

## Create のスティッカーを CC で使う（AshCC の追加ブロックではない）

Create 6 のスティッカーはそのまま CC の周辺機器になる（型 `Create_Sticker`）。Sable があると、伸ばしたスティッカーが前面に触れている別の機体（または地面）のブロックと**固定ジョイント**で結合する（ドッキング・係留）。リンクハブ経由でも使える。

| 関数 | 説明 |
|---|---|
| `extend()` / `retract()` / `toggle()` | 伸ばす・縮める・切り替え。状態が変わったら `true`（すでにその状態なら `false`） |
| `isExtended()` | 伸びているか |
| `isAttachedToBlock()` | 伸びていて、前面に**同じ座標空間の**くっつけられるブロックがあるか。Sable で別の機体に結合しているときは `false` になりうる |

- レッドストーン信号でも動くので、信号が入っていると Lua の操作が上書きされる
- 結合の判定は Sable 側（前面の先を調べ、距離と角度が許容内なら固定ジョイント）。結合したかは Lua からは直接取れないので、下の `dock` は「相手の機体が自分と同じ速度で動くか」で判定する

テストプログラム `lua/sticker_test.lua`:
- `sticker_test [名前] [--keep]` — 全スティッカーで 縮める→伸ばす→二重操作が false→toggle を確認し、PASS/FAIL を `sticker_test.log` に。最後は縮めて終わる（--keep で元の状態に戻す）
- `sticker_test dock [名前] [秒]` — 伸ばして、最寄りの機体との相対速度を数秒見る（AshCC のレーダーが必要。機体上なら CC: Sable の `sublevel` で自分の速度も使う）
  - `[LOCKED]` 相対速度 ≈0 で、どちらかが動いている → 結合している
  - `[FREE]` 相手が独立して動いている → 結合していない（届いていない・角度がずれている）
  - `[STILL]` どちらも止まっていて判定できない → 片方を動かして再実行
- `sticker_test undock [名前]` — 縮めて、離れられるかを見る
- `sticker_test manual [名前]` — e/r/t で操作、最寄りの機体との相対速度を表示

## サーバー設定（`serverconfig/ashcc-server.toml`）
- seeker: `defaultAngle`(15°), `defaultDistance`(512), `flareBias`(10°)
- hdMonitor: `maxWidth`(16), `maxHeight`(12), `syncRange`(96), `maxFrameBytes`(16 MiB)
- imu: `gravity`(9.8)
- decoyLauncher: `capacity`(64), `maxSalvo`(8), `requireAmmo`(true), `cooldownSeconds`(0.5), `spread`(15°), `flareSpeed`(25), `flareSeconds`(6), `flareGravity`(4), `flareDrag`(0.96), `chaffSpeed`(15), `chaffSeconds`(8), `chaffGravity`(0.5), `chaffDrag`(0.85)
- radar: `defaultRange`(2048), `maxResults`(256), `minWatchInterval`(5 tick)
- link: `range`(0 = 無制限), `interdimensional`(true), `maxLinks`(64)
- flapBearing: `defaultSpeed`(90°/秒), `maxSpeed`(720°/秒), `maxAngle`(180°), `redstoneAngle`(45°、レッドストーンフラップベアリングの信号15での角度)
- servoBearing: `defaultSpeed`(90°/秒), `maxSpeed`(720°/秒), `stiffness`(1600), `damping`(40), `friction`(0.3)（剛性・減衰は慣性モーメントあたり。スイブルベアリングと同じ既定値）
- antigravity: `maxSpeed`(20), `maxAcceleration`(20), `maxAngularSpeed`(45°/秒), `maxAngularAcceleration`(90°/秒²), `speedCap`(100), `accelerationCap`(100), `angularSpeedCap`(360), `angularAccelerationCap`(720)
- thruster: `maxThrust`(2000), `maxGimbal`(30°), `throttleRate`(1 = 即時), `gimbalRate`(1 = 即時)
- laser: `range`(256), `blockPower`(0.5), `breakBlocks`(true), `dropBlocks`(false), `damageOwnSubLevel`(false), `entityDamage`(4), `igniteSeconds`(3), `impactForce`(100), `recoil`(true), `aimSpeed`(180°/秒), `maxFireSeconds`(10), `cooldownSeconds`(5)

## 開発
### ゲーム内テスト環境
```
gradlew runTestClient
```
- Create 6.0.10・Create Aeronautics 1.3.0・CC: Sable・Veil・JEI を CurseForge インスタンスの mods から `run-test/mods` へ同期して起動（CC: Tweaked と Sable は開発クラスパスのもの）。インスタンスの場所は `-PinstanceDir=...` で変更できる
- 初回はスーパーフラット・クリエイティブ・チート有効のワールド `ashcc_test` を自動作成（昼固定・天候固定・モブ湧きなし）し、レーダー・スラスター・アドバンスドコンピューター・モニター・有線モデムを配る。2回目以降はそのワールドを直接開く。別ワールドは `-Pworld=名前`
- ワールドをやり直すときは `run-test/saves/ashcc_test` を消す
- 仕組み: `devtest/TestWorld`（`-Dashcc.testworld` のときだけ動く。jar からは除外）

### ビルド・自動テスト
- `gradlew build` → `build/libs/ashcc-<ver>.jar`
- `gradlew runGameTestServer` — GameTest（エンティティの方位、見越し計算、Sable 機体の組み立て→地上/機上の両方から検知、スラスターの出力・ジンバル・レッドストーン、機体の上昇と回転、リンクハブ、フラップベアリングの組み立て・角度・分解、レッドストーンフラップベアリングの側面＋・背面−・相殺・コンパレーター、シーカーの角度・距離の絞り込みと6方向の座標系、4方向フラップの独立動作と破壊時の復元、レーザーの焼き切り・照準・エンティティ・過熱、機体の焼き切り・押す力・反動・機体の回転への追従、サーボベアリングの組み立て・組み立て後に置いたブロックが一緒に回る・180° をまたぐ近回り・0° に戻って分解・空のターンテーブル、内蔵 imu の四元数から角速度・地上の値とストリーム・落下する機体で 0 g・機体の回転と yawRate の一致・ポケットの向き、IFF の付与・無効化・コード変更、発射機の装填・クールダウン・弾切れ、チャフがレーダーだけに・フレアがシーカーだけに映ること、フレアが機体より優先されること、反重力コアの重力打ち消し・2個載せても1つ分・切ると落ちる・速度・指定点への移動・指定の向きへの回転・回転速度、createarms のラック発射・情報・目標変更・自爆・他のラックから操作できないこと、VLS 発射とセル無し・空のエラー、機体 ID 目標を追うこと、CIC のキュー発射、散布機、CBC の砲架でのレールガンの組み立て・充電・角度の向き・発射・冷却と組み立てエラー、照準計算と CBC の実弾の飛び方の一致、ロータリー機関砲の回転上昇・連射・回転低下、レーザー砲の電力なしで撃たない・給電後に鉄ブロックを焼き切る・熱と電力・引き金を離すと止まる）。CBC は `-PnoCbc` で外せる（createarms は CBC を使うので `-PnoArms` も付ける）。プレイヤーがいないと Sable は機体を水平に動かさないので、横方向の移動は回転（トルク）で確認している。`-PnoAero` / `-PnoSable` / `-PnoCreate` で Aeronautics・Sable・Create 無しの起動も確認できる（GameTest の Create と Aeronautics は `run-gt/mods` にインスタンスから同期。Aeronautics は Sable と Create が要るので `-PnoSable` / `-PnoCreate` のときは自動で外す）
- GameTest の createarms は `run-gt/mods` にインスタンスから同期（GeckoLib と、createarms が宣言せずに読み込む Create Big Cannons・Ritchie's Projectile Lib も）。`-PnoArms` で外せる。コンパイルは `libs/createarms/` の jar
- Sable は公開 Maven が無いので `libs/` のjar（インスタンスからコピー）に対してコンパイルしている。Simulated（サーボベアリング）は Aeronautics の jar-in-jar から取り出した `libs/create/simulated-neoforge-1.21.1-1.3.0.jar`
- `gradlew runTestClient -PlaserShot -Pworld=ashcc_lasershot` — 地上と機体上のレーザーを撃たせて `run-test/screenshots` に撮影して終了（`devtest/LaserShot`）
- テスト環境（`run-test`・`run`）にはインスタンスの Sodium・Iris（Veil・Flywheel 連携も）・Distant Horizons が常に入り、シェーダーパック（既定 Complementary Reimagined、`-Pshaders=パック名.zip` で変更）が有効になる。`-PnoShaders` でシェーダー無効、`-PnoDH` で Distant Horizons を外す。DH の GC 警告はチャットに出さない設定にする。2026-10-25 時点で、レーザー・レーザー砲（地上・機体上）・レールガンの演出・高精細モニターは DH＋シェーダーありでも表示されることを確認済み
- `gradlew runTestClient -PpanelShot -Pworld=ashcc_panel` — コントロールパネルで F-16 風のコックピット（MFD×2・ベゼルボタン・キーパッド・HUD・傾けたサイドコンソールのスティックとスロットル）を組み、周辺機器経由で MFD と HUD に描き、実際のキーでスティックとスロットルを同時につかんで操作して撮影（`devtest/PanelShot`）
- `gradlew runTestClient -PcbcShot=ship -Pworld=ashcc_cbcship` — Sable の機体に載せたレーザー砲を照射しながら旋回させる
- `gradlew runTestClient -PcbcShot=laser -Pworld=ashcc_cbclaser` — レンズ4枚のレーザー砲を Creative Generator で給電し、照射しながら左右に振って石の壁を焼き切る（`cbc_laser_*`）
- `gradlew runTestClient -PcbcShot=power -Pworld=ashcc_cbcpower` — Crafts & Additions の Creative Generator を砲架と組み立て前の尾栓の隣に置き、手で充電せずに満タンになって撃てることをログで確認
- `gradlew runTestClient -PcbcShot=fx -Pworld=ashcc_cbcfx` — レールガンの演出だけを真横・砲の後ろ・弾が脇を通る位置から撮影（`cbc_fx_*`）。ソニックブームはログに `RailgunFx: sonic crack` と出る
- `gradlew runTestClient -PcbcShot -Pworld=ashcc_cbc` — CBC の砲架3台（レールガン・ロータリー機関砲・CBC の鋼鉄オートキャノン）を組み立て、Java から照準・発射して壁を撃ち、続けて反重力コアで浮かせた標的の機体を2台のコンピューターの実際の `lua/cbc_turret.lua` に狙わせる。ログ（`CbcShot`、砲塔の `cbc_turret.log` も写す）と `run-test/screenshots` に記録して終了（`devtest/CbcShot`）
- `gradlew runTestClient -PantigravShot -Pworld=ashcc_antigrav` — 反重力コアとコンピューターの機体を作り、実際の `lua/antigrav_pilot.lua fly 5` にキー入力（Space・W・E・W・X）を送って飛ばし、ログ（`AntigravShot`）と `run-test/screenshots` に記録して終了（`devtest/AntigravShot`）。`-PantigravShot=test` なら `lua/antigrav_test.lua` を実行して結果をログに写す
- `tools/gen_textures.py`（仮テクスチャ）, `tools/gen_structure.py` / `gen_structure_17.py`（テスト用の空の構造物）
