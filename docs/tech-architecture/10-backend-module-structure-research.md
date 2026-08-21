---
title: 后端模块结构调研：Maven module 与 package 划分的一手资料
slug: backend-module-structure-research
description: 针对"Java/Spring Boot 项目如何划分 Maven module 与 package"的一手资料调研——Maven 官方多模块机制与依赖管理、Spring Boot Structuring Your Code 原文建议、Spring Modulith 的 ApplicationModule 模型与 verify() 边界校验、package by layer vs by domain 的原始论证（Uncle Bob / Simon Brown）、Spring PetClinic / Modulith 示例 / gs-multi-module 三个官方结构参照、executable jar 与业务 jar 分离的官方模式，最后给出对 Axis 现状的适用性分析。
status: knowledge
tags: [maven, multi-module, package-structure, spring-boot, spring-modulith, modular-monolith, architecture, research]
created: 2026-08-19
---

# 后端模块结构调研：Maven module 与 package 划分的一手资料

> 调研目标：回答"Java/Spring Boot 后端项目如何划分 Maven module 与 package 结构"，全部结论来自一手来源（maven.apache.org、docs.spring.io、spring.io 官方博客、原始作者文章），每条结论附 URL。文末给出对 Axis（Spring Boot 4 + Java 21，1 人 + AI 开发，未上线）的适用性分析。本文只回答"外部世界怎么做、官方怎么说"，不产出重构方案。

调研问题：

1. Maven 官方对多模块项目的组织建议、标准目录布局、依赖管理最佳实践；何时拆 Maven module、何时 package 就够
2. Spring Boot 官方 "Structuring Your Code" 的原文建议
3. Spring Modulith 的模块模型、包结构约定、边界校验、事件交互；逻辑模块 vs 物理 Maven 模块的取舍
4. package by layer vs package by feature/domain 的原始论证
5. 知名开源 Spring 项目的实际结构参照
6. 可执行 jar 与业务 jar 分离是否官方/社区公认模式

---

## Q1. Maven 官方：多模块机制是"构建编排"，不是"架构切分标准"

### Reactor 与聚合/继承

Maven 官方把多模块机制称为 **reactor**，职责是：收集可用模块 → 按依赖关系排序（保证被依赖者先构建）→ 按序构建。排序依据依次为：模块间依赖、插件声明、插件依赖、构建扩展，最后才是 `<modules>` 里的声明顺序；注意 `dependencyManagement` 和 `pluginManagement` **不影响**排序（[Guide to Working with Multiple Modules](https://maven.apache.org/guides/mini/guide-multiple-modules.html)；Maven 4 版本同内容见 [Guide to Working with Multiple Subprojects in Maven 4](https://maven.apache.org/guides/mini/guide-multiple-modules-4.html)）。

常用命令行开关：`--also-make`（连带构建依赖）、`--also-make-dependents`（连带构建依赖方）、`--resume-from`、`--fail-fast`（默认）/`--fail-at-end`、`--non-recursive`（同上一链接）。

### 标准目录布局

[Introduction to the Standard Directory Layout](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html) 规定了 `src/main/java`、`src/main/resources`、`src/test/java` 等固定位置，动机原话：*"Having a common directory layout allows users familiar with one Maven project to immediately feel at home in another Maven project."* 顶层只放 `pom.xml` + `src/` + `target/` + 说明文档；多模块项目里每个子模块都按同样布局递归。它只约束目录，不约束 package 怎么分。

### 依赖管理最佳实践

[Introduction to the Dependency Mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html) 给出三条与本题直接相关的官方实践：

- **`dependencyManagement` 收口版本**：父 POM 集中声明依赖版本/scope/exclusions，子模块只写 `groupId`+`artifactId`。它同时管控传递依赖的版本（dependency management 优先于 dependency mediation）。
- **BOM（`scope=import`）**：单继承限制下，用 `type=pom + scope=import` 导入一组相关构件的版本清单；官方给出的典型用法就是"多模块库对外发布 BOM 供使用方导入"。Spring Boot 自己的 `spring-boot-dependencies` 就是该模式的实例。
- **显式声明直接用到的依赖**：*"it is a good practice to explicitly specify the dependencies your source code uses directly"*——不要隐式依赖传递链上顺带进来的构件，否则上游一旦移除该依赖你的构建就挂；可用 `dependency:analyze` 检查。

### 何时拆 module，官方怎么说？

**Maven 官方文档本身不回答这个问题**——它只给机制（reactor、聚合、继承、依赖管理），不给架构判据。Sonatype 的 *Maven by Example* 第 6 章（[A Multi-module Project](https://books.sonatype.com/mvnex-book/reference/multimodule.html)）给出的多模块样例是 `simple-parent`（pom）+ `simple-weather`（业务 jar）+ `simple-webapp`（war，依赖 weather），即按"产物类型/部署单元"切分，而非按业务域切分。

与本题相关、有官方出处的拆 module 判据实际来自 Spring 生态（见 Q5/Q6）：**有可独立复用的库产物**、**可执行应用与库的打包方式不同**时拆 Maven module；业务域边界的表达官方交给了 package 结构（Q2/Q3）。

---

## Q2. Spring Boot 官方：root package 放主类，典型布局就是"按域分包"

[Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html) 开篇即声明 *"Spring Boot does not require any specific code layout to work. However, there are some best practices that help."*，并直接在页内提示：**"If you wish to enforce a structure based on domains, take a look at Spring Modulith."**——Spring Boot 官方把"按域组织代码"的强制方案明确指向 Spring Modulith。

三条具体建议：

- **避免 default package**：用 `@ComponentScan`/`@EntityScan`/`@SpringBootApplication` 时，default package 会导致扫描所有 jar 里的所有类。
- **主类放在其他类之上的 root package**：`@SpringBootApplication` 隐式定义了 base "search package"——JPA 的 `@Entity` 扫描、component scan 都以主类所在包为根；放 root package 还能让 component scan 只作用于本项目。
- **官方给出的典型布局本身就是按域分包**（原文树）：主类 `MyApplication.java` 在 `com.example.myapplication`，其下是 `customer/` 与 `order/` 两个域包，**每个域包内部平铺 entity + controller + service + repository**（`Customer.java`、`CustomerController.java`、`CustomerService.java`、`CustomerRepository.java`）。即 Spring Boot 官方示范的不是 `controller/service/repository` 按层分包，而是 package by domain。

多模块场景下的扫描机制（来自官方指南 [gs-multi-module](https://spring.io/guides/gs/multi-module)）：当主类所在包与库代码包不同根时，需 `@SpringBootApplication(scanBasePackages = "com.example.multimodule")` 显式指定父包；指南同时警告 **"Do not use the same package as the library (or a parent of the library package) unless you want to include all Spring components in the library by `@ComponentScan`"**，并提醒一旦显式配置了 `scanBasePackages`，`@EntityScan` 与 `@EnableJpaRepositories` 也可能需要显式配置（它们只在未显式指定时继承主类包）。

---

## Q3. Spring Modulith：官方"逻辑模块"方案，包即模块、测试即边界校验

### 模块模型与包结构约定

Spring Modulith 的定位（[项目页](https://spring.io/projects/spring-modulith)）：构建结构良好的 Spring Boot 应用，**模块由领域驱动（application modules driven by the domain）**，支持结构校验、单模块集成测试、模块级观测、文档生成。

模块定义（[Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)）：一个 application module = 对外的 provided interface（Spring bean + 发布的应用事件）+ 不应被外部访问的内部实现 + 对其他模块的 required interface。默认约定：

- 主类所在包为 main package，**其直接子包 = application module base package**；
- 无子包的简单模块可用 Java package-private 可见性隐藏内部类，模块 API 即包内 public 类型；
- 有子包时，base package 是 **API package**（允许其他模块引用），**所有子包一律 internal**（其他模块禁止引用）——官方特意指出痛点：*"in plain Java, packages are not hierarchical"*，`order.internal` 里的 public 类编译器管不住别的模块引用它，这正是 Modulith 要补的洞；
- 进阶：`@ApplicationModule` 支持嵌套模块（1.3+）、`allowedDependencies` 显式白名单、`Type.OPEN` 开放模块（官方明确说它面向存量项目渐进改造，*"using open application modules usually hints at sub-optimal modularization"*）、`@NamedInterface` 暴露额外 SPI 包。

官方介绍博客（[Introducing Spring Modulith](https://spring.io/blog/2022/10/21/introducing-spring-modulith), 2022-10-21）给出的动机原文：框架传统上按技术概念提供结构引导（`@Controller`/`@Service`/`@Repository`），但 *"shifting the focus to align code structure with the domain has proven to lead to better structured applications that are ultimately more understandable and maintainable"*。

### 边界校验 verify()

[Verification](https://docs.spring.io/spring-modulith/reference/verification.html)：`ApplicationModules.of(Application.class).verify()` 一个测试方法完成全部校验，规则三条——模块间依赖必须构成 **DAG（禁止循环）**；**跨模块访问只能经由 API package**（对 internal 包的引用直接拒绝）；可选的显式 `allowedDependencies` 白名单。违反即抛异常断构建；`detectViolations()` 可拿到违规列表做过滤/豁免。底层用 ArchUnit 实现，还能叠加 jMolecules 的 DDD/hexagonal 架构规则。

### 事件驱动的模块交互

[Events](https://docs.spring.io/spring-modulith/reference/events.html)：*"their primary means of interaction should be event publication and consumption"*——跨模块直接注入对方 bean 会产生 "functional gravity"，官方推荐改用 `ApplicationEventPublisher` 发领域事件；`@ApplicationModuleListener`（= `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener`）是默认集成方式；配套的 **Event Publication Registry** 把事件发布日志随业务事务落库（JPA/JDBC/MongoDB/Neo4j starter），监听失败可重投，2.x 增加发布生命周期（PUBLISHED/PROCESSING/COMPLETED/FAILED/RESUBMITTED）与 stale 监控；事件还可经 `@Externalized` 外发到 Kafka/AMQP/JMS。测试侧有 `@ApplicationModuleTest`（只引导单个模块的 slice 集成测试）+ `PublishedEvents` 断言事件发布。

### 逻辑模块 vs 物理 Maven 模块的取舍，官方怎么说？

- Modulith 的模块**完全基于包结构**（`ApplicationModules.of(主类)` 分析主类包下的代码），与物理 Maven module 无关——它是"单部署单元内的逻辑模块化"方案。
- 官方对复杂度的态度（Fundamentals 引言）：*"Spring Modulith provides different ways of expressing modules ... differing in the level of complexity involved ... allows developers to **start simple** and naturally move to more sophisticated means as and if needed."* 默认约定零注解、零配置，verify() 一个测试即可引入——对 1 人项目没有仪式性负担。
- 官方没有说"逻辑模块可以替代物理模块"；两者正交：Maven module 是**构建/发布/打包单元**（Q1/Q6），Modulith module 是**单体内的领域边界与可见性规则**。介绍博客的语境是"monolithic, modular systems"对微服务的回摆，即先在单体内部把边界做对。

---

## Q4. package by layer vs by feature/domain：原始出处

### Robert C. Martin —— Screaming Architecture（2011）

[Screaming Architecture](https://blog.cleancoder.com/uncle-bob/2011/09/30/Screaming-Architecture.html)（Uncle Bob 博客，2011-09-30）：看建筑蓝图你能一眼认出"住宅/图书馆"——架构会 scream 它的用途。对应地：*"When you look at the top level directory structure, and the source files in the highest level package; do they scream: Health Care System, or Accounting System? Or do they scream: Rails, or Spring/Hibernate?"* 核心主张：架构围绕 use case 组织，框架是工具不是架构本身，交付机制（Web/DB）是应该推迟决定的 detail。这是"顶层包结构应该表达领域"这个论点最常被引用的原始出处。

### Simon Brown —— Package by Component（2013–2016）

Simon Brown（C4 模型作者）在其个人站的内容合集页 [Modular monolith and "package by component"](https://simonbrown.je/modular-monolith/) 中重发了该文（原载 codingthearchitecture.com，2016 年重发、内容可追溯至 ~2013；该内容的一个版本还以 "The Missing Chapter" 收入 Uncle Bob 的 *Clean Architecture*, 2017）。原文定位即标题：**"An alternative to package by layer, package by feature, and ports & adapters/hexagonal architecture"**。文中对 package by layer 的定义：*"the traditional 'horizontal' layered architecture, where we separate our code based upon what it does from a technical perspective"*（按技术职能水平切分）。他的替代方案是把 web/service/data-access 收进同一个按业务组件划定的垂直切片包内，让"组件"而不是"层"成为代码组织的第一级单位。该文的经典出处 URL 为 [Package by component and architecturally-aligned testing](http://www.codingthearchitecture.com/2015/03/08/package_by_component_and_architecturally_aligned_testing.html)（2015-03-08；本次调研环境中该站点正文无法抓取，内容经作者本人重发页核实）。

### 官方文档侧的呼应

- Spring Boot 的典型布局（Q2）示范的正是按域分包；
- Spring 团队在 Modulith 介绍博客里明说"让代码结构与领域对齐已被证明带来更好理解性/可维护性的结构"（Q3 引文）；
- Spring Modulith 文档则给出了"按层分包为什么管不住"的技术解释：层间全靠 public 类型互相可见，Java 编译器对跨层/跨域引用没有任何强制力，于是只能靠约定——而约定会被时间侵蚀，所以需要 verify() 这类机制。

---

## Q5. 开源/官方示例的实际结构（均为一手仓库核实）

### Spring PetClinic：单 Maven module + 按域分包

[spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic)：单个 Maven 模块；`org.springframework.samples.petclinic` 根包下是 `PetClinicApplication.java` + `model/` + `owner/` + `vet/` + `system/` 四个域包（[目录核实](https://github.com/spring-projects/spring-petclinic/tree/main/src/main/java/org/springframework/samples/petclinic)）。以 `owner/` 为例（[目录核实](https://github.com/spring-projects/spring-petclinic/tree/main/src/main/java/org/springframework/samples/petclinic/owner)），一个包内同时装下所有层：`Owner.java`（entity）、`OwnerController.java`、`OwnerRepository.java`、`Pet.java`、`PetController.java`、`Visit.java`、`VisitController.java`、`PetValidator.java`、`PetTypeFormatter.java`。即 Spring 官方最知名的 sample 应用用的就是 Spring Boot 文档那套"按域分包"。

### Spring Modulith 官方示例：单 Maven module + 域包 + internal 子包

[spring-projects/spring-modulith 的 spring-modulith-examples](https://github.com/spring-projects/spring-modulith/tree/main/spring-modulith-examples)：每个 example（如 `spring-modulith-example-full`）都是单模块项目，结构为 `example/Application.java` + `example.inventory/` + `example.order/`（+ `example.order.internal/`）（[目录核实](https://github.com/spring-projects/spring-modulith/tree/main/spring-modulith-examples/spring-modulith-example-full/src/main/java/example)）——与 Q3 的默认约定完全一致。

### gs-multi-module：官方两模块模板 library + application

[spring.io/guides/gs-multi-module](https://spring.io/guides/gs/multi-module)：根 POM（packaging=pom）聚合两个模块——

- **`library`**：业务库 jar。**完全不引入 spring-boot-maven-plugin**（原话：*"The main function of the plugin is to create an executable 'über-jar', which we neither need nor want for a library."*）；依赖刻意收窄（用 `org.springframework.boot:spring-boot` 而非 starter，避免拖入过多传递依赖）；官方明确**不建议在 library 里放 `application.properties`**（classpath 上只会加载一份，会与使用方冲突）。
- **`application`**：可执行应用，含主类 `DemoApplication`（`@SpringBootApplication(scanBasePackages = "com.example.multimodule")`），以 `${project.version}` 依赖 `library`；构建运行 `./mvnw install && ./mvnw spring-boot:run -pl application`。

这是 Spring 官方对"业务 jar + 启动模块"最直接的结构示范（与 Axis 的 axis-service/axis-agent 划分同构）。

---

## Q6. 可执行 jar 与业务 jar 分离：是官方模式

### spring-boot-maven-plugin 的 repackage 语义

[Spring Boot Maven Plugin – Packaging](https://docs.spring.io/spring-boot/maven-plugin/packaging.html)：`repackage` goal 把 `package` 阶段产出的普通 jar/war 重写为 `java -jar` 可执行归档（类进 `BOOT-INF/classes`、依赖进 `BOOT-INF/lib`），默认替换原产物（原产物改名 `.original`）。关键原文（Custom Classifier 一节）：

> *"By default, the `repackage` goal replaces the original artifact with the repackaged one. **That is a sane behavior for modules that represent an application but if your module is used as a dependency of another module, you need to provide a classifier for the repackaged one.** The reason for that is that application classes are packaged in `BOOT-INF/classes` so that the dependent module cannot load a repackaged jar's classes."*

即官方在插件文档里直接区分了两类模块：**"代表一个应用"的模块**（repackage 成可执行 fat jar）与**"被其他模块当依赖用"的模块**（要么加 classifier 保留普通 jar，要么干脆不 repackage）。被 repackage 的 jar 类被挪进 `BOOT-INF/classes`，天然不能再当普通依赖用——这从机制上强制了两种角色的分离。

### "application 模块 vs library 模块"的官方组合拳

gs-multi-module（Q5）把这条语义落成模板：library 模块**不加** Boot 插件、不打可执行 jar、依赖收窄、不放 `application.properties`；application 模块独占主类 + Boot 插件 + 可执行产物。两文档合起来就是"bootstrap/executable 模块与业务模块分离"的官方说法——它不是社区偏方，而是 Spring 官方指南与插件文档共同支撑的模式。

---

## 对 Axis 现状的适用性分析

Axis 现状：父 POM + `axis-service`（业务核心 jar）+ `axis-agent`（可执行 fat jar，含唯一主类）；痛点是 axis-service 内部"按层分包"与"按域分包"混用、`knowledge` 包横跨两个 Maven 模块、`digest` 逻辑与 REST 入口分居两模块、`axis-agent` 定位模糊。对照上述一手资料：

- **现有两模块划分本身符合官方模式**。axis-service（无 Boot 插件的业务 jar）+ axis-agent（主类 + repackage fat jar）正是 gs-multi-module 的 library/application 结构与 spring-boot-maven-plugin 文档区分的两类模块（Q5/Q6）。"axis-agent 既是启动器又含业务 controller"在官方语境里不是问题——`application` 模块本来就允许写代码（gs 示例主类同时是 `@RestController`）；需要权衡的只是它该不该成为业务代码的常规落点。
- **痛点本质是 package 层问题，不是 Maven module 层问题**。按层 vs 按域混用、同名包跨模块，官方答案一致：主类 root package 之下按域分包（Q2 典型布局 + PetClinic 实证），并用机制而非纪律守边界（Q3 verify() 会当场拒绝 `knowledge` 被从非法位置引用、模块循环依赖）。gs-multi-module 对"不要与 library 用相同包名/父包"的警告，正对应 `knowledge` 包横跨两模块的现状。
- **1 人 + AI 开发项目尤其受益于"可执行的架构规则"**。Simon Brown/Uncle Bob 的论证（Q4）解释了按层分包为何守不住（全靠 public 可见性 + 团队自觉）；单人项目没有团队评审，AI 生成代码更不会自觉守约定，而 `ApplicationModules.verify()` 把边界变成 CI 里一条会红的测试，成本只有一个测试类、默认约定零注解——官方明确支持"start simple"（Q3）。
- **不需要为业务域再拆 Maven module**。官方给出的拆 module 判据是"独立产物/不同打包方式/复用边界"（Q1/Q6），不是"一个域一个模块"；Inbox/Issue/Knowledge 这类域边界在官方范式里由包 + Modulith 表达。反过来，digest 这种"逻辑在 service 模块、REST 入口在 agent 模块"的跨模块分布，属于模块职责划分问题，官方资料未提供直接判据，需要结合"application 模块该不该放业务"这一本地决策解决。
- **事件交互的官方路径与 Axis 现有约定同向**。Modulith 把"跨模块交互首选应用事件"作为默认推荐（Q3），Axis 已有"事件监听器收口到领域 `listener/` 包"的约定，方向一致；Event Publication Registry（事务内落库 + 失败重投）对 Daily Digest 这类定时批处理与业务解耦有参考价值，但引入与否超出本调研范围。

---

## 结论

1. **Maven module 是构建/打包/发布单元，不是领域边界工具**：Maven 官方只提供 reactor 机制与依赖管理实践（父 POM `dependencyManagement` 收口版本、BOM import、显式声明直接依赖），从不回答"何时按业务拆模块"；官方可见的拆分判据是"独立产物类型 + 打包方式"（可执行应用 vs 库）。
2. **Spring Boot 官方推荐主类居 root package、其下按域分包**："Structuring Your Code"的典型布局就是 `customer/`、`order/` 各装全层类，并把"按域强制结构"直接指向 Spring Modulith。
3. **Spring Modulith 是官方"单体内逻辑模块"方案**：直接子包即模块、base package 为 API、子包全 internal、`verify()` 一条测试强制 DAG 与 API-only 访问、事件为跨模块首选交互；默认约定零成本起步，与物理 Maven module 正交，适合 1 人项目用机制替代自律。
4. **package by domain/feature 的原始论证充分**：Uncle Bob 的 Screaming Architecture（顶层结构应尖叫领域而非框架）、Simon Brown 的 Package by Component（水平分层按技术职能切分是其定义的反面；组件垂直切片为其替代），Spring 官方文档与示例全面站队按域。
5. **官方参照物结构高度一致**：PetClinic 与 Modulith 示例均为"单 Maven module + 按域包"，gs-multi-module 为"library 业务 jar（无 Boot 插件）+ application 可执行模块（主类 + fat jar）"——Axis 的两模块骨架与后者同构，问题集中在包结构与模块职责，而非模块数量。
6. **可执行 jar 与业务 jar 分离是官方模式**：spring-boot-maven-plugin 文档明确区分"代表应用的模块"与"被依赖的模块"，repackage 产物因 `BOOT-INF/classes` 布局天然不能再当依赖，机制上保证了分离。

### 参考来源（全部一手）

- Maven: [Guide to Working with Multiple Modules](https://maven.apache.org/guides/mini/guide-multiple-modules.html) / [Maven 4 版](https://maven.apache.org/guides/mini/guide-multiple-modules-4.html) / [Standard Directory Layout](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html) / [Introduction to the Dependency Mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html) / [Maven by Example Ch.6](https://books.sonatype.com/mvnex-book/reference/multimodule.html)
- Spring Boot: [Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html) / [gs-multi-module](https://spring.io/guides/gs/multi-module) / [Maven Plugin – Packaging](https://docs.spring.io/spring-boot/maven-plugin/packaging.html)
- Spring Modulith: [项目页](https://spring.io/projects/spring-modulith) / [Introducing Spring Modulith](https://spring.io/blog/2022/10/21/introducing-spring-modulith) / [Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html) / [Verification](https://docs.spring.io/spring-modulith/reference/verification.html) / [Events](https://docs.spring.io/spring-modulith/reference/events.html) / [examples 仓库](https://github.com/spring-projects/spring-modulith/tree/main/spring-modulith-examples)
- 原始作者: [Screaming Architecture (R.C. Martin, 2011)](https://blog.cleancoder.com/uncle-bob/2011/09/30/Screaming-Architecture.html) / [Simon Brown: Modular monolith and "package by component"](https://simonbrown.je/modular-monolith/) / [Package by component and architecturally-aligned testing (2015 原文)](http://www.codingthearchitecture.com/2015/03/08/package_by_component_and_architecturally_aligned_testing.html)
- 示例仓库: [spring-petclinic](https://github.com/spring-projects/spring-petclinic)（[根包](https://github.com/spring-projects/spring-petclinic/tree/main/src/main/java/org/springframework/samples/petclinic) / [owner 包](https://github.com/spring-projects/spring-petclinic/tree/main/src/main/java/org/springframework/samples/petclinic/owner)）

> 调研局限说明：Simon Brown 2015 年原文页面在本环境中无法抓出正文（站点对抓取不友好，web.archive.org 不可达），其内容经作者本人在 simonbrown.je 的重发页核实后引用；JHipster 生成结构（按层分包的知名对照例）因仓库 API 抓取失败未纳入，如需要可补充。
