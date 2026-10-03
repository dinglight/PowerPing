# PowerPing 软件设计文档

> 版本 v1.0 · 日期 2026-10-03 · 平台 Android（minSdk 24 / targetSdk 37 · Kotlin + Compose Material3）

---

## 1. 产品概述

| 项 | 内容 |
|---|---|
| 应用名称 | PowerPing（电量提醒） |
| 一句话定位 | 充电到设定阈值时自动响铃，提醒用户拔掉充电器，保护电池健康 |
| 目标用户 | 关注电池寿命、习惯长时间插电（如夜间/办公充电）的 Android 用户 |
| 核心价值 | 锂电池长期满电搁置会加速老化；80%~90% 断充可显著延长电池循环寿命。PowerPing 在"充满"前主动打断 |
| 典型场景 | 睡前充电 → 到 85% 闹铃响 → 拔掉充电器；办公位常插电 → 到阈值提醒断电 |

设计原则：**零轮询、事件驱动**（不耗电、不费流量）；**一次充电只提醒一次**；**打扰最小化**（常驻通知静默，仅到阈值才响）。

## 2. 功能需求

| 编号 | 功能 | 描述 | 优先级 |
|---|---|---|---|
| F1 | 电量实时监控 | 前台服务监听系统电量变化广播，实时更新状态 | P0 |
| F2 | 阈值触发闹铃 | 充电达到阈值（默认 85%，可调 50%–95%）时触发 | P0 |
| F3 | 循环闹铃 | 循环播放系统闹钟铃声，直到用户点"停止" | P0 |
| F4 | 单次充电单次提醒 | 本次充电只响一次；84↔85% 电量抖动不重响；拔线后重新武装 | P0 |
| F5 | 自启与复活 | 开机自启、插电自动拉起服务；被系统限制时降级为可点击通知 | P1 |
| F6 | 闹铃试听 | 一键预览提醒铃声 5 秒 | P2 |
| F7 | 环境自检提示 | 闹钟音量为 0、通知权限被拒、国产 ROM 后台限制时在界面提示 | P2 |

非功能需求：常驻通知 IMPORTANCE_LOW 静默；服务进程内存占用 < 30MB；监控状态无轮询（仅广播驱动），额外耗电趋近于 0。

## 3. 系统架构

### 3.1 模块划分

```
┌──────────────────────────────────────────────┐
│ UI 层  MainActivity + MonitorScreen (Compose) │
│   状态卡片 / 阈值滑块 / 主开关 / 试听 / 说明卡   │
└───────────────┬──────────────────────────────┘
                │ StateFlow（MonitorBus）        │ Intent（START/STOP/STOP_ALERT/APPLY_SETTINGS）
┌───────────────▼──────────────────────────────▼┐
│ 服务层  ChargeMonitorService（前台服务 specialUse）│
│   状态机 · 电量广播接收 · 触发判定 · 通知更新      │
├───────────────┬──────────────────────────────┤
│ 提醒层  AlarmPlayer   │ 通知层  Notifications   │
│ MediaPlayer 循环      │ 双渠道 + 降级通知        │
├──────────────────────────────────────────────┤
│ 数据层  PrefsRepository（SharedPreferences）    │
├──────────────────────────────────────────────┤
│ 系统交互  BatteryManager / BootReceiver /       │
│          PowerConnectionReceiver / Alarm 流     │
└──────────────────────────────────────────────┘
```

### 3.2 关键技术决策

**D1 前台服务类型 = `specialUse`**
电量监控不属于标准 FGS 类型：`health` 语义为健身追踪（审核不过）、`mediaPlayback` 名不符实、`dataSync` 有 6 小时时限且 Android 15 起禁止开机启动。`specialUse` 是官方为"不落入其他类别的合理长时用例"提供的类别，无时限，只需声明权限与用途说明属性。

**D2 监控机制 = sticky 广播，零轮询**
服务内动态注册 `ACTION_BATTERY_CHANGED`。该广播为 sticky：注册瞬间立即收到最近一次电量，之后每次电量/插拔状态变化系统主动推送。不需要 AlarmManager 定时唤醒，Doze 模式下插着充电器时电量变化照常推送。电量计算统一 `level × 100 / scale`（不假设 scale=100）。

**D3 specialUse 的声明方式**：`FOREGROUND_SERVICE_SPECIAL_USE` 权限 + service 节点 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性（英文说明用途：电池健康保护监控）。

### 3.3 状态机

```
                 ┌─────────────────────────────────────────┐
                 │                                         │
  ┌──────┐ 开启  ▼      插电且<阈值    达到阈值     点"停止" │  拔线(重置标志)
  │ IDLE ├──────► ARMED ─────────► MONITORING ──► ALERTING┤
  └──────┘       ▲  ▲              │               │       │
     ▲           │  │   点"停止"    │插电但已提醒过   │       │
     │  关闭监控  │  └──────────────┴──────────► DONE ◄──────┘
     └───────────┴────────────────────────────────┘
```

| 当前态 | 事件 | 次态 | 动作 |
|---|---|---|---|
| IDLE | 用户开启监控 | ARMED 或 MONITORING | 启动前台服务，读 sticky 电量初始化 |
| ARMED | 插电且电量 < 阈值 | MONITORING | 更新常驻通知"充电监控中" |
| ARMED | 插电且电量 ≥ 阈值 | ALERTING | 置已提醒标志 + 启动闹铃（含 90% 插入即响的场景） |
| MONITORING | 电量 ≥ 阈值 | ALERTING | **置 `alerted_this_charge=true`** + 启动闹铃 + 高优先级通知 |
| MONITORING | 拔线 | ARMED | 更新通知"等待充电" |
| ALERTING | 用户点通知"停止" | DONE | 停止闹铃（**标志保留**，防抖核心） |
| DONE | 拔线 | ARMED | **重置 `alerted_this_charge=false`**，等待新周期 |
| ALERTING | 拔线（边响边拔） | ARMED | 停铃 + 重置标志 |
| 任意运行态 | 用户关闭监控 | IDLE | 停铃、stopSelf |

**防抖机制（F4）**：电量百分比在阈值附近可能 84↔85 反复跳动。首次达到阈值即置 `alerted_this_charge` 标志并响铃；此后即使电量回落再回升，因标志已置位不会重响（进入 DONE）。标志**持久化到磁盘**——服务进程被杀重建后也不会重复响铃。只有拔掉充电器才重置，进入下一充电周期。

### 3.4 服务生命周期与启动入口

| 入口 | 场景 | 保障机制 |
|---|---|---|
| UI 主开关 | 用户主动开启 | 前台 Activity 启动服务，永远允许 |
| 点通知 | 用户点常驻/降级通知 | "用户操作 UI 元素"属后台启动豁免 |
| `POWER_CONNECTED` 清单广播 | 进程已死时插电 | 该广播在隐式广播豁免清单（可静态注册）；启动服务 try/catch，失败则发**降级通知**"已接入充电器，点我开始监控" |
| `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` | 重启/升级后 | 同上 try/catch 降级 |
| `START_STICKY` | 进程被系统杀死后重建 | `onStartCommand` 收到 null intent 时按 `monitoring_enabled` 恢复监控 |
| `onTaskRemoved` | 用户划掉应用 | 不做处理——正要继续监控 |

降级路径是关键容错：Android 12+ 限制后台启动前台服务，且受限类型清单逐版本扩大；所有后台入口均以"尝试启动 → 失败发通知 → 用户点击（豁免场景）启动"兜底，未来平台收紧无需改代码。

### 3.5 闹铃实现

- **铃声来源**：`RingtoneManager.getDefaultUri(TYPE_ALARM)` 优先（跟随用户设定的闹钟铃声）；获取失败回退到内置合成铃声 `res/raw/alarm_fallback.wav`（自合成的 2 秒双音 beep，无版权风险）。
- **播放**：`MediaPlayer` + `isLooping=true` 原生循环（不用 Ringtone——不支持可靠循环）；`AudioAttributes(USAGE_ALARM, CONTENT_TYPE_SONIFICATION)` 使音量跟随**系统闹钟音量**，与媒体音量互不影响，且勿扰模式下用户允许闹钟即可响。
- **停止**：通知"停止提醒" action / 界面停止按钮 → `stop()`；播放器所有操作幂等，`onDestroy` 强制释放。
- **WAKE_LOCK**：仅 ALERTING 期间持有部分唤醒锁，防止息屏 Doze 下播放暂停；其他状态不持有。

## 4. 界面设计（单屏 · 中文）

```
┌─────────────────────────────────┐
│  PowerPing          [通知权限提示]│
│ ┌─────────────────────────────┐ │
│ │        ● 85%   ⚡充电中       │ │   ← 状态卡片：大号电量 + 充电图标
│ │   状态：充电监控中             │ │   ← 状态 chip（中文映射见下）
│ │   ▓▓▓▓▓▓▓▓▓▓░░░ 距目标 0%    │ │   ← 距阈值进度条
│ └─────────────────────────────┘ │
│  目标电量            85%        │
│  ├───────●──────────┤           │   ← Slider 50–95
│                                   │
│  [ 开始监控 / 停止监控 ]           │   ← 主开关大按钮
│  [ 试听闹铃 ]                     │
│ ┌─────────────────────────────┐ │
│ │ 说明：开机自动监控；若手机管家  │ │   ← 说明卡片
│ │ 杀后台请允许自启动；提醒音跟随  │ │
│ │ 系统闹钟音量                  │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

状态文案映射：

| 状态 | 界面文案 | 颜色语义 |
|---|---|---|
| IDLE | 已停止 | 灰 |
| ARMED | 已就绪 · 等待充电 | 蓝 |
| MONITORING | 充电监控中 | 绿 |
| ALERTING | 正在提醒 · 请拔掉充电器 | 红 |
| DONE | 本次充电已提醒 · 可拔充电器 | 橙 |

交互细节：
- 服务运行时界面数据来自服务的 StateFlow；服务未运行时 Activity 自行注册 sticky 电量广播显示实时电量。
- 阈值滑块改动即保存；服务运行中发送 `APPLY_SETTINGS` 立即生效（无需重启服务）。
- 开启监控前（Android 13+）先弹 `POST_NOTIFICATIONS` 系统权限；被拒时服务仍可运行、闹铃仍响，仅通知不可见，界面给出提示。
- 试听按钮：循环播放 5 秒自动停止（走同一 AlarmPlayer）。

## 5. 权限与合规

| 权限 | 级别 | 用途 |
|---|---|---|
| `FOREGROUND_SERVICE` | normal | API 28+ 前台服务必需 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal | API 34+ specialUse 类型必需 |
| `POST_NOTIFICATIONS` | **运行时（33+）** | 显示通知；开启监控前申请 |
| `RECEIVE_BOOT_COMPLETED` | normal | 开机自启 |
| `WAKE_LOCK` | normal | ALERTING 期间保持播放 |

Manifest 关键声明（示意）：

```xml
<service android:name=".service.ChargeMonitorService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Battery charge monitor: plays repeating alarm at user-set charge threshold so user can unplug, protecting battery health"/>
</service>

<receiver android:name=".receiver.BootReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
    </intent-filter>
</receiver>

<receiver android:name=".receiver.PowerConnectionReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.ACTION_POWER_CONNECTED"/>
        <action android:name="android.intent.action.ACTION_POWER_DISCONNECTED"/>
    </intent-filter>
</receiver>
```

（清单静态注册的 receiver 设 `exported="false"` 仍能收到系统广播——exported 只约束第三方应用。）

## 6. 数据设计

SharedPreferences（文件名 `power_ping_prefs`）——三个字段均为低频读写，不需要数据库：

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `threshold_percent` | Int | 85 | 触发阈值，范围 50–95 |
| `monitoring_enabled` | Boolean | false | 用户主开关；重启/进程重建后据此恢复 |
| `alerted_this_charge` | Boolean | false | 本次充电已提醒标志；拔线重置（防抖 + 防重复响铃） |

内存态（不持久化）：`MonitorStatus(serviceRunning, state, batteryPct, charging, threshold, alarmVolumeZero)` 经 `MonitorBus` StateFlow 暴露给 UI。

## 7. 通知设计

| 渠道 | Importance | 行为 | 内容 |
|---|---|---|---|
| `monitor` | LOW（静默常驻） | 常驻通知栏，不出横幅 | 按状态显示"充电监控中 xx%→目标85%" / "已就绪，等待充电"；点击进主界面 |
| `alert` | HIGH（横幅） | 到阈值时发出 | "已达 85%，请拔掉充电器"；含**"停止提醒"** action（`STOP_ALERT`）；渠道声音置 null（铃声由 AlarmPlayer 播放，避免双重声音） |
| 降级通知（monitor 渠道） | HIGH | 后台启动失败时 | "已接入充电器，点我开始监控"，点击打开 Activity 并自动启动服务 |

## 8. 边界与风险

| 风险 | 策略 |
|---|---|
| 国产 ROM（MIUI/EMUI/ColorOS）强杀后台、默认禁自启 | 三重兜底：START_STICKY + 插电广播复活 + 降级通知；说明卡引导用户开"自启动/无限制省电"。已尽力，属平台限制 |
| 勿扰模式 | USAGE_ALARM 由用户 DND 设置决定是否可响；不绕过，说明卡提示 |
| 闹钟音量为 0 | 触发时检测 `STREAM_ALARM`，为 0 则通知文案追加"闹钟音量为 0，可能听不到"；不自动改用户音量 |
| Doze 息屏 | 广播驱动无轮询不受影响；ALERTING 期间持 WAKE_LOCK |
| 用户 force-stop 应用 | 平台语义：所有静态 receiver 失效直到用户再次打开应用；写进说明卡 |
| 电量上报粒度 | 统一 `level × 100 / scale`，适配非 100 粒度设备 |
| 插线时已超阈值（如 90% 插入） | 视为预期：立即触发提醒 |
| targetSdk 37 超前平台行为 | 所有后台启动 try/catch + 通知降级，平台收紧时零改动优雅降级 |
| 上架 Google Play | specialUse 需在 Play Console 申报用途并可能要求演示；个人侧载/国内分发无此要求 |

## 9. 测试与验证方案

`adb shell dumpsys battery` 可注入虚拟电量并触发真实系统广播，覆盖全部场景（模拟器可用）：

| 用例 | 操作 | 预期 |
|---|---|---|
| T1 进入监控 | `set ac 1 && set status 2 && set level 50` | 通知变"充电监控中"，状态 MONITORING |
| T2 触发提醒 | `set level 85` | 闹铃循环响，alert 横幅出现 |
| T3 防抖 | `set level 84` → `set level 85` | 不重响（DONE） |
| T4 停止提醒 | 点通知"停止提醒" | 铃停，状态 DONE |
| T5 拔线重置 | `dumpsys battery unplug` | 状态 ARMED，通知"等待充电" |
| T6 新周期 | `set ac 1` → `set level 85` | 再次触发响铃 |
| T7 超阈值插入 | 阈值 85 时 `set level 90` 插入 | 立即触发 |
| T8 START_STICKY | `adb root && adb shell kill <pid>` | 进程重建后监控继续 |
| T9 重启自启 | 开关开启后 `adb reboot` | 开机后服务运行或出现降级通知（不崩溃） |
| T10 音量为 0 | `media volume --stream 4 --set 0` 后触发 | 通知含"闹钟音量为 0"提示 |
| T11 阈值边界 | 滑块设 50 / 95 各触发一次 | 均正确触发 |

---

*参考：Android 官方文档 — 前台服务类型（specialUse）、后台启动 FGS 限制与豁免清单、Android 15 行为变更、隐式广播豁免清单。*
