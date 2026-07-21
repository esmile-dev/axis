---
status: in-progress
feature: 用户头像上传
created: 2026-07-20
---

# 任务清单：用户头像上传（示例）

## 任务表
| ✓ | 编号 | 任务内容 | 对应 FR | 主要涉及文件 | 验收点 | Commit |
|---|------|----------|---------|--------------|--------|--------|
| ☑ | T-001 | users 表加 avatar_url 列（新 migration） | FR-001 | db/migration | 应用启动无报错 | a1b2c3d |
| ☑ | T-002 | 上传接口 + 大小/类型校验 + 单测 | FR-001 | UserController 等 | curl 传 3MB 返回 400；1MB 返回 200 | b2c3d4e |
| ☑ | T-003 | 前端 AvatarUploader（选择+裁剪+预览） | FR-001 | ProfilePage.vue | 选图后可裁剪预览 | c3d4e5f |
| ☐ | T-004 | 对接上传接口 + 资料页/导航栏展示头像 | FR-002 | user store、AppHeader | 上传后刷新仍在 | |

## 验收记录
| AC 编号 | 结果 | 验证方式 |
|---------|------|----------|
| AC-001 | ✓ | 传 3MB 出现提示；传 1MB 成功显示（截图见 PR） |
| AC-002 | ✓ | 上传后 F5 刷新头像仍在 |

**结论**：通过
