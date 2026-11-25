# Android 的照片与权限

首次打开光印即可试用示例、从系统相册选照片、导入 RAW 文件或打开作品。选择器只把选中的照片、文件或文件夹交给光印。设备缺少系统照片选择器时，AndroidX 会改用系统文件选择器。导入文件夹时，目录范围由系统授权页显示。[Android 照片选择器](https://developer.android.com/training/data-storage/shared/photo-picker) · [Android 文件和目录选择](https://developer.android.com/training/data-storage/shared/documents-files)

## 应用内相册

`library` 渠道可在首页点「启用应用内相册」。`picker` 渠道始终使用系统选图或文件入口。两种渠道都可以把成片保存到 `Pictures/光印`，也能在系统文件选择器里为 `.kaiphoto` 选择保存位置。

| 系统 | 启用应用内相册时请求的权限 | 用户可选的范围 |
| --- | --- | --- |
| Android 10–12L | `READ_EXTERNAL_STORAGE` | 允许读取共享存储中的媒体，或只从选择器导入 |
| Android 13 | `READ_MEDIA_IMAGES` | 允许读取图片，或只从选择器导入 |
| Android 14 及以上 | `READ_MEDIA_IMAGES`、`READ_MEDIA_VISUAL_USER_SELECTED` | 授权全部或部分照片。已授权部分照片时，点「调整可访问的照片」可重新选择 |

用户拒绝图库权限后仍可选文件。如果系统停止显示权限弹窗，再点管理入口会提供「打开系统设置」和「只选照片」。回到应用后，光印重新查询实际授权。部分授权可以在权限名和授予状态均相同的情况下改变可见照片，所以应用也会刷新图库、缩略图和照片信息。某张草稿原片失去访问权限时，点「重新选择照片」可沿用当前模板。[Android 部分照片访问](https://developer.android.com/about/versions/14/changes/partial-photo-video-access) · [运行时权限与降级使用](https://developer.android.com/training/permissions/requesting)

## 水印地点与原片 EXIF

自动地点来自照片已有的 EXIF；用户也可以手填或留空。选择城市、坐标或导出时勾选保留 GPS 后，未读到坐标的系统照片可以点「从文件选择本张原片」，只替换这一张的来源，保留模板、手填地点和同批其他照片。取消选取不会修改当前照片。

如果使用 `library` 渠道的应用内相册，完整授权时可以点「允许读取照片内的拍摄地点」。部分授权时另设「管理相册与地点权限」；确认后系统会一起询问图片范围及 `ACCESS_MEDIA_LOCATION`，仍可以选择有限范围。系统可能继续隐藏部分授权照片的坐标，用户可选原文件或手填。某些版本也会随「始终允许全部」一起授予读取照片地点的权限，界面按系统实际授予结果更新。获准后，应用通过 MediaStore 读取原始媒体；如果原片没有坐标，仍可手填地点。调整系统授权后返回应用，旧的位置缓存会失效。

系统选图、云端分享和部分文件提供方可能交付已去掉位置的副本。两种渠道都可以读取所选文件本身已有的可读 EXIF。应用不申请设备的实时定位或后台定位权限。把地点印在画面上、把 GPS 写进成片，以及 v2 作品包随附授权原片，是三个单独的选择。[Android 照片位置元数据](https://developer.android.com/training/data-storage/shared/media)

## 批量导出与通知

导出会先进入任务队列。需要在通知栏看进度时，点「导出记录 → 开启进度通知」。Android 13 及以上在此操作时请求 `POST_NOTIFICATIONS`，拒绝后导出照常运行，进度和结果仍可在导出记录查看。用户也可在系统设置里单独开启应用通知或「照片导出」通知渠道。

后台进行大图或批量导出时，光印使用前台服务并按系统要求提交服务通知。Android 13 及以上拒绝通知权限后，系统任务管理器仍可显示该服务。应用不申请管理所有文件、通讯录、视频或录音权限。[Android 通知运行时权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission) · [Android 共享媒体访问](https://developer.android.com/training/data-storage/shared/media)

## 已验证的系统行为

Android 10 模拟器上，拒绝相册权限后通过系统文件选择器打开 JPEG、编辑并导出，图库和照片位置权限仍为关闭。Android 14 模拟器上，授权范围从 1 张增加到 2 张，再改为全部 3 张和撤销后 0 张，应用内相册随之变化。该系统镜像在设置中选择「始终允许全部照片」时同时授予了照片位置权限，光印返回前台后按实际状态更新界面。Android 16 模拟器上也验证了部分授权重选，以及拒绝通知后完成导出。
