# 单点行人抓拍 Android 原型

这是一个原生 Android 离线单点值守抓拍 APP 工程骨架，面向 Android 10 及以上设备。

当前已实现：

- 关注人员底库录入：姓名/编号、性别、衣着、配饰、体态、照片 URI。
- 后置摄像头实时预览：基于 CameraX。
- 本地匹配流程：阈值、命中、去重、抓拍、弹窗、震动。
- 本地记录管理：抓拍时间、目标、分数、匹配模式、图片路径。
- 本地循环记录：最多保留 300 条，锁定字段已预留。
- 完全本地存储：SharedPreferences + App 私有图片目录。

当前识别实现说明：

- `OfflineMatcher` 是可替换的离线识别接口占位实现，用于跑通业务闭环。
- 生产版本应将 `OfflineMatcher.match(...)` 替换为 TFLite/NNAPI 模型推理：
  - 人脸检测与人脸特征向量提取。
  - 人体检测、衣着/配饰属性识别。
  - 行人 ReID 或人体特征向量比对。
  - 遮挡判断与人脸/人体双策略融合评分。
- 10 秒预警视频录制依赖 CameraX VideoCapture，依赖已加入，当前版本已预留字段，建议在抓拍命中后异步启动录制并写入 `CaptureRecord.videoPath`。

## 打开方式

1. 用 Android Studio 打开本目录。
2. 等待 Gradle 同步依赖。
3. 连接 Android 10+ 真机。
4. 运行 `app`。

当前本机已验证：

- `./gradlew :app:assembleDebug` 构建成功。
- Debug APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。
- 已复制一份到 `outputs/pedestrian-capture-debug.apk`。
- 项目使用 `work/android-sdk` 作为本项目专用 Android SDK，避免依赖系统 SDK 写权限。

## 后续生产化清单

- 接入真实离线模型文件，并在首次启动时完成模型加载。
- 增加警戒区域绘制层，只对 ROI 内人员做识别。
- 增加管理员/操作员登录与权限控制。
- 增加记录导出 ZIP/CSV。
- 增加后台前台服务通知，支撑长时间值守。
- 增加暗光增强、相机参数策略和温控降频策略。
- 对人脸与行人特征数据做本地加密保存。
