---
status: done
feature: ai-config-profiles
created: 2026-07-24
---

# 复盘总结：AI 配置多档案管理

## 验收结论

G4 通过。关键验证：
- 后端新增 `ai_config_profile` 表，支持多套配置档案；运行时仅 active profile 生效。
- 前端 settings 页改为卡片列表，可新增/编辑/删除/测连/切换 active。
- 切换 active 后 `/api/v1/config/ai` 立即返回新配置；digest/chat 下次调用自动走新 `ChatClient`。
- 删除唯一 active 档案返回 409；编辑时留空 apiKey 保持原加密值不变。
- 旧 `app_config` 扁平配置在启动时自动迁移为首个 profile。

## 做得好的 / 可复用经验

1. **数据模型与运行时分离**：profile 表存多套，服务层只暴露一个 active `ChatClient`，对 digest/chat 零侵入。
2. **平滑迁移**：首次启动自动把 digest-2.0 的 `ai.api_key/endpoint/model` 迁到新表，老用户无感升级。
3. **兼容旧 API**：原 `/api/v1/config/ai/*` 继续映射到 active profile，旧版 settings 不会炸。
4. **前端状态简洁**：列表即真相，切换/删除后重新拉列表，避免本地状态与后端不同步。

## 踩坑记录

1. **Maven reactor 与 spring-boot:run 冲突**：从 backend 根目录执行 `-pl axis-agent -am` 时，spring-boot-maven-plugin 会在父 POM 上找 main class 失败。解决：先 `mvn install -pl axis-service -am`，再进入 `axis-agent` 目录单独 `mvn spring-boot:run`。
2. **Mockito strict stubbing**：`activateProfile` 测试里 `legacyConfigRepository.findById` 的 stub 在 reload 路径未命中，触发 `UnnecessaryStubbingException`。解决：只 stub 真正会被调用的方法。
3. **删除唯一 active 返回 500**：最初未处理 `IllegalStateException`。解决：在 `ConfigController` 加 `@ExceptionHandler`，返回 409 + 可读 message。

## 变更文件

- 新增：`backend/axis-service/src/main/java/com/esmile/axis/entity/AiConfigProfile.java`
- 新增：`backend/axis-service/src/main/java/com/esmile/axis/repository/AiConfigProfileRepository.java`
- 修改：`backend/axis-service/src/main/java/com/esmile/axis/config/AiConfigService.java`
- 修改：`backend/axis-service/src/main/java/com/esmile/axis/controller/ConfigController.java`
- 修改：`frontend/app/pages/settings.vue`
- 修改：`backend/axis-service/src/test/java/com/esmile/axis/config/AiConfigServiceTest.java`
- 修改：`backend/axis-service/src/test/java/com/esmile/axis/controller/ConfigControllerTest.java`
- 修改：`backend/axis-service/src/main/resources/application.yml`
- 修改：`.env.example`
