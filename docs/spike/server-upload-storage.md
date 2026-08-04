---
name: server-upload-storage
description: 本地上传（UPLOAD_DIR 落盘）未来部署到服务器时的演进方向：统一抽象 Storage 层，本地磁盘实现切换为对象存储实现。本 spike 记录现状盘点与迁移路径，待服务器部署立项时执行。
status: draft
---

# Spike: 本地 upload → 服务器对象存储

## 1. 背景

当前所有上传都落在应用本地磁盘（`${app.upload.dir}`，默认 `./uploads`），本地单人使用没问题；一旦部署到服务器（尤其多实例/容器），本地盘方案失效：

- 多实例：文件写在 A 实例的盘上，后续 GET 打到 B 实例 → 404
- 容器/重新部署：本地盘随容器销毁，文件全丢
- 备份割裂：`pg_dump` 不含文件，文件要单独 rsync

本 spike 只记方向与路径，**不做选型终裁**——真正立项时机是「准备部署到服务器」。

## 2. 现状盘点（两个写路径、一个读路径）

| 路径 | 代码 | 说明 |
|---|---|---|
| 图片上传 | `FileUploadService`（`POST /api/upload` → `UPLOAD_DIR/UUID.ext`） | issue 描述等粘贴图片，返回 `/api/uploads/{filename}` |
| 知识库导入 | `KnowledgeFileStorage`（`UPLOAD_DIR/knowledge/UUID.ext`） | 书/文档原件，`file_path` 落库，**不经 HTTP 对外** |
| 读取 | `FileUploadController GET /api/uploads/{filename}` | 应用中转读盘（带路径穿越防护） |

值得注意的利好：`knowledge_item.file_path` 只存**相对路径**，不含本地绝对路径——迁移不需要改表改数据。

## 3. 演进路径（两步走，第一步无论如何都要做）

**第一步：收敛 Storage 抽象（无行为变化的重构）**

抽一个 `Storage` 接口（`put / get / delete / url`），两个写路径都改走它，当前实现 = `LocalDiskStorage`。这步是纯重构（S/M 级），可以先做，为切换铺平道路。

**第二步：新增对象存储实现，配置切换**

- 存储选型倾向 **S3 兼容 API**（AWS S3 / 阿里 OSS / 自建 MinIO 一套 API 通吃），SDK 引入后以 `S3Storage` 实现同一接口，`app.storage.type: local|s3` 配置切换
- 读取路径两选一（立项时定）：
  - **应用中转**（现状延续）：实现最简单、鉴权统一，代价是带宽过应用——个人量级够用，**默认推荐**
  - **Presigned URL 直连**：浏览器直连对象存储，省应用带宽，但要做签名签发与权限设计，量级大了再上
- 迁移既有文件：一次性脚本把 `UPLOAD_DIR/**` 推入 bucket（相对路径即 object key，`file_path` 零改动）

## 4. 明确不做的

- 不在本 spike 做 CDN、图片缩略/转码、多 region——上线后再议
- 不改 `file_path` 语义（保持相对路径/object key 同一含义）

## 5. 结论

- 触发时机：准备服务器部署时立项（M 级：抽象 + 一种云实现 + 迁移脚本）
- 前置无阻塞：当前本地盘方案继续用，`file_path` 相对路径设计已天然兼容迁移
- 第一步的 Storage 抽象若提前做，归为「不改变行为的重构」豁免档

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-04 | 初稿 | 用户提出：后续本地 upload 要集成到服务器上传 |
