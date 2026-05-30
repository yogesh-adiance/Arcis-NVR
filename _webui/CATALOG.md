# Vendor web-UI parity catalog

Harvested from `http://192.168.12.254/dist/build.js` (Vue SPA, 852 KB).
Every option below must exist in the Android app.

## Top-level tabs (router)

| Web route   | Android nav destination     |
|-------------|------------------------------|
| /preview    | `live`                       |
| /playback   | `playback`                   |
| /setting    | `settings`                   |
| /manage     | `manage` (channel add/edit)  |

## Preview tab
- `MainView` mosaic (1/4/9/16 channels, layout switcher)
- Per-tile: live RTSP, channel label, snapshot, talk-back (if supported), record on/off
- `Ptz` overlay (8-way + zoom/focus/iris + preset 1–255)
- `DeviceList` sidebar
- `Playback` quick jump

## Playback tab
- `R.SearchRecord` — list recordings (per-channel, date, time-range)
- Calendar (`CalendarPicker`)
- 24-h timeline scrubber
- ExoPlayer transport (play/pause/step ±1f, ±30s, 2x/4x)
- Download segment (optional)

## Setting tab (sidebar tree)

### Ordinary → `/setting/ordinary` + `/setting/deviceinfo`
- `DeviceInfo`: DeviceName, DeviceModel, HWID, FWVersion, BuildTime, UID  → `/netsdk/Stat/DeviceInfo`
- `General` (language, daylight savings, video-standard, overwrite policy) → `GET/PUT /netsdk/General`
- `LocalTime` + `UtcTime` time-sync → `/netsdk/LocalTime`, `/netsdk/S.SetLocalTime`, `/netsdk/UtcTime`

### Video → `/setting/videosetting` + children
- `ColorSetting` (brightness/contrast/saturation/hue per channel) → `/netsdk/Stream/Color`
- `EncodingSettings` (resolution / FPS / bitrate / GOP / H.264-H.265, main + sub) → `/netsdk/Stream/Encode`
- `StreamValue` (live bitrate read-back) → `/netsdk/GetBitrate`
- `ChannelDetail` → `/netsdk/GetChannelDetail`
- `ChannelOSD` (channel-name overlay, time overlay, position)

### Network → `/setting/network`
- `Ordinary` (LAN: DHCP/static/IP/Mask/GW/DNS, HTTP/RTSP ports) → `GET/PUT /netsdk/Network`
- `EmailSetting` → `GET/PUT /netsdk/Network/SMTP`, `PUT /netsdk/R.TestSmtp`
- `WifiSetting` → `GET/PUT /netsdk/Network/WIFI`, `/netsdk/S.Wifi.RegionChannel`, `/netsdk/R.Wifi.Reset`
- PPPoE → `GET/PUT /netsdk/Network/PPPoE`, `R.ReStartPPPoE`, `R.StopPPPoE`, `G.PPPoEInfo`

### `VideoDetection` (motion / video-loss / video-blind)
- `GET/PUT /netsdk/Event` (event config), `GET/PUT /netsdk/Record` (schedule trigger)

### `PTZSetting`
- `GET/PUT /netsdk/Channel/PTZ` (protocol, baudrate, address)

### `AlarmSetting`
- `GET/PUT /netsdk/Event` (alarm-in, alarm-out linkages, email/buzzer/record actions)

### `SystemMaintenance` → factory / upgrade
- `PUT /netsdk/Reboot`
- `/netsdk/GetUpgradeRate` (firmware upgrade progress)
- `FactorySetting` (factory reset)

### `UserManagement` → `/setting/user`
- `GET /netsdk/User`, `PUT /netsdk/AddUser`, `PUT /netsdk/DelUser`, `PUT /netsdk/SetPasswd`

### `ChangePassword` → `/netsdk/SetPasswd`

### `LogInformation` → `/netsdk/LogSearch`, `/netsdk/LogPageChange`

### `HardDiskSetting`
- `GET /netsdk/Stat`, `GET /netsdk/Stat/IPC` (per-disk + per-IPC usage)

### `RecordTimeTable`
- `GET/PUT /netsdk/Record` (per-channel 7-day x 24-h schedule grid)
- `CopyTo` (copy schedule across channels)

## Manage tab (Add Camera)
- `ChannelList` — table of NVR channels with assigned IPC summary  → `GET /netsdk/Channel`
- `R.SEARCH.Ipc` — LAN scan for ONVIF/private cameras
- `EditChannel` form  → `PUT /netsdk/Channel/IPCamInfo` (`/netsdk/Channel/IPCamInfo/<n>` for single)
- `R.Channel.SetIpcReboot` — reboot connected IPC
- `R.Channel.SetImageRollover` — flip image

## All endpoints found in build.js (raw list)

```
GET  /netsdk/Channel
GET  /netsdk/Channel/IPCamInfo
PUT  /netsdk/Channel/IPCamInfo
PUT  /netsdk/Channel/IPCamInfo/<n>
PUT  /netsdk/Channel/PTZ
PUT  /netsdk/R.SEARCH.Ipc
PUT  /netsdk/R.Channel.SetIpcReboot
PUT  /netsdk/R.Channel.SetImageRollover
GET  /netsdk/Stream
GET  /netsdk/Stream/Color
PUT  /netsdk/Stream/Color
GET  /netsdk/Stream/Encode
PUT  /netsdk/Stream/Encode
GET  /netsdk/GetBitrate
GET  /netsdk/GetChannelDetail
GET  /netsdk/Network
PUT  /netsdk/Network
GET  /netsdk/Network/PPPoE
PUT  /netsdk/Network/PPPoE
PUT  /netsdk/R.ReStartPPPoE
PUT  /netsdk/R.StopPPPoE
GET  /netsdk/G.PPPoEInfo
GET  /netsdk/Network/SMTP
PUT  /netsdk/Network/SMTP
PUT  /netsdk/R.TestSmtp
GET  /netsdk/Network/WIFI
PUT  /netsdk/Network/WIFI
PUT  /netsdk/S.Wifi.RegionChannel
PUT  /netsdk/R.Wifi.Reset
GET  /netsdk/General
PUT  /netsdk/General
GET  /netsdk/Stat
GET  /netsdk/Stat/IPC
GET  /netsdk/Stat/DeviceInfo
GET  /netsdk/Event
PUT  /netsdk/Event
GET  /netsdk/Record
PUT  /netsdk/Record
PUT  /netsdk/R.SearchRecord
GET  /netsdk/LocalTime
PUT  /netsdk/S.SetLocalTime
GET  /netsdk/UtcTime
GET  /netsdk/User
PUT  /netsdk/AddUser
PUT  /netsdk/DelUser
PUT  /netsdk/SetPasswd
PUT  /netsdk/LogSearch
PUT  /netsdk/LogPageChange
PUT  /netsdk/Reboot
GET  /netsdk/GetUpgradeRate
```
