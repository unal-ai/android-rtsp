# RTSP Camera - Android 摄像头 RTSP 推流应用

将 Android 手机摄像头视频实时转换为 RTSP 视频流，供其他应用使用。

## 功能特性

- 📹 **实时摄像头推流** - 将手机摄像头视频转为 RTSP 流
- 🔄 **前后摄像头切换** - 支持切换前置/后置摄像头
- ⚙️ **可配置参数** - 支持调整分辨率和比特率
- 📋 **一键复制 URL** - 方便快速分享 RTSP 地址
- 📊 **连接监控** - 实时显示客户端连接数
- 🎯 **低延迟** - 使用硬件编码，延迟低

## 技术栈

- **RootEncoder** - 视频/音频编码库
- **RTSP-Server** - RTSP 服务器插件
- **Camera2 API** - 现代摄像头接口
- **Kotlin** - 开发语言

## 默认配置

| 参数       | 值       |
| ---------- | -------- |
| 默认端口   | 8554     |
| 视频分辨率 | 1280x720 |
| 视频比特率 | 2.5 Mbps |
| 视频帧率   | 30 FPS   |
| 视频编码   | H.264    |
| 音频编码   | AAC      |
| 音频采样率 | 44100 Hz |

## 构建

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34 (Android 14)
- 最低支持 Android 5.0 (API 21)

### 构建步骤

```bash
# 克隆项目
cd android-rtsp

# 构建 Debug APK
./gradlew assembleDebug

# 或使用 Android Studio 打开项目
```

APK 输出位置: `app/build/outputs/apk/debug/app-debug.apk`

## 使用方法

### 1. 安装并启动应用

将 APK 安装到 Android 设备，首次启动时授予摄像头和麦克风权限。

### 2. 开始推流

1. 确保手机和客户端在同一 WiFi 网络
2. 点击 **"开始推流"** 按钮
3. 应用会显示 RTSP URL，例如: `rtsp://192.168.1.100:8554/`

### 3. 客户端连接

#### 使用 VLC 播放器
```
打开 VLC -> 媒体 -> 打开网络串流 -> 输入 RTSP URL
```

#### 使用 FFmpeg
```bash
ffplay rtsp://192.168.1.100:8554/
```

#### 使用 Python OpenCV
```python
import cv2

cap = cv2.VideoCapture("rtsp://192.168.1.100:8554/")

while True:
    ret, frame = cap.read()
    if ret:
        cv2.imshow('RTSP Stream', frame)
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
```

#### 使用 AI 虚拟鼠标项目
```bash
python -m src.main --source "rtsp://192.168.1.100:8554/"
```

## 项目结构

```
android-rtsp/
├── app/
│   ├── build.gradle.kts          # 应用构建配置
│   ├── proguard-rules.pro        # ProGuard 规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 应用清单
│       ├── java/com/rtsp/camera/
│       │   └── MainActivity.kt   # 主活动
│       └── res/
│           ├── layout/           # 布局文件
│           ├── values/           # 资源值
│           ├── drawable/         # 图形资源
│           ├── mipmap-*/         # 应用图标
│           └── xml/              # XML 配置
├── build.gradle.kts              # 根项目配置
├── settings.gradle.kts           # Gradle 设置
├── gradle.properties             # Gradle 属性
└── README.md                     # 本文档
```

## 故障排除

### 无法获取 IP 地址
- 确保手机已连接 WiFi
- 检查网络权限是否已授予

### 客户端无法连接
- 确保手机和客户端在同一局域网
- 检查手机防火墙设置
- 尝试关闭移动数据，仅使用 WiFi

### 视频卡顿
- 降低视频分辨率 (设置 -> 分辨率: 640x480)
- 降低视频比特率
- 确保 WiFi 信号良好

### 编码器初始化失败
- 某些设备可能不支持特定分辨率
- 尝试使用其他分辨率设置

## 许可证

MIT License

## 致谢

- [RootEncoder](https://github.com/pedroSG94/RootEncoder) - 视频编码库
- [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) - RTSP 服务器插件
