# FabricMC 模组开发文档 — Better Aerodynamics

## 📋 版本信息

| 组件 | 版本 |
|------|------|
| Minecraft | **26.1.2** |
| Fabric Loader | **0.19.3** |
| Fabric API | **0.153.0+26.1.2** |
| Java | **25** |
| Fabric Loom | **1.17-SNAPSHOT** |

## 📚 官方文档入口

- **开发者指南总览**: https://docs.fabricmc.net/develop/
- **fabric.mod.json 详解**: https://docs.fabricmc.net/develop/loader/fabric-mod-json
- **项目结构说明**: https://docs.fabricmc.net/develop/getting-started/project-structure
- **Mixin 教程**: https://docs.fabricmc.net/develop/mixins/bytecode
- **事件系统 (Fabric API)**: https://docs.fabricmc.net/develop/events
- **网络通信**: https://docs.fabricmc.net/develop/networking
- **调试指南**: https://docs.fabricmc.net/develop/debugging

---

## 1. ModInitializer — 模组主入口

**文件**: `src/main/java/com/betteraerodynamics/BetterAerodynamics.java`

```java
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BetterAerodynamics implements ModInitializer {
    public static final String MOD_ID = "betteraerodynamics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 游戏加载模组时调用（服务端 + 客户端）
        LOGGER.info("Hello Better Aerodynamics world!");
    }
}
```

### `onInitialize()` — 方法说明

| 属性 | 值 |
|------|-----|
| **触发时机** | Minecraft 启动、模组加载就绪后 |
| **运行环境** | 服务端 + 客户端（通用代码） |
| **注意事项** | 此时部分资源可能尚未初始化，谨慎访问资源 |

### `LOGGER` — 日志记录器

```java
// 基本用法
LOGGER.info("普通信息");       // INFO 级别
LOGGER.warn("警告信息");       // WARN 级别
LOGGER.error("错误信息", e);   // ERROR 级别（带异常）
LOGGER.debug("调试信息");      // DEBUG 级别
```

**文档**: https://docs.fabricmc.net/develop/debugging — 日志会输出到控制台和日志文件。

### `MOD_ID` — 模组标识符

- **用途**: 全局唯一的模组 ID，用于资源命名、网络包标识等
- **约定**: 小写字母 + 下划线，如 `"my_mod"`
- **文档**: https://docs.fabricmc.net/develop/loader/fabric-mod-json — `id` 字段

---

## 2. Identifier — 资源定位符

**文件**: `src/main/java/com/betteraerodynamics/BetterAerodynamics.java` (第 27-29 行)

```java
public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MOD_ID, path);
}
```

### `Identifier.fromNamespaceAndPath(namespace, path)` — 静态方法

| 参数 | 说明 |
|------|------|
| `namespace` | 命名空间，通常为 MOD_ID |
| `path` | 资源路径，如 `"textures/block/stone.png"` |
| **返回值** | `Identifier` 对象，格式为 `namespace:path` |

### 常见用法

```java
// 物品 ID
Identifier myItem = id("my_item");

// 纹理路径
Identifier texture = Identifier.fromNamespaceAndPath(MOD_ID, "textures/block/my_block.png");

// 音效事件
SoundEvent sound = SoundEvent.of(id("entity.my_entity.hurt"));

// 网络包（Fabric API）
CustomPacketPayload.Type TYPE = new CustomPacketPayload.Type(
    Identifier.fromNamespaceAndPath(MOD_ID, "custom_payload")
);
```

**文档**: https://docs.fabricmc.net/develop/networking — `Identifier` 用于资源定位和网络通信。

---

## 3. ClientModInitializer — 客户端入口

**文件**: `src/client/java/com/betteraerodynamics/client/BetterAerodynamicsClient.java`

```java
import net.fabricmc.api.ClientModInitializer;

public class BetterAerodynamicsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 仅客户端环境调用（渲染、音效、GUI等）
    }
}
```

### `onInitializeClient()` — 方法说明

| 属性 | 值 |
|------|-----|
| **触发时机** | 客户端游戏启动、模组加载就绪后 |
| **运行环境** | 仅客户端（Client side only） |
| **典型用途** | 注册渲染器、GUI、音效、按键绑定等 |

### 常见用法示例

```java
@Override
public void onInitializeClient() {
    // 注册块实体渲染器
    BlockEntityRenderers.register(MyBlockEntityType.INSTANCE, MyRenderer::new);

    // 注册按键绑定（MC 1.20+）
    KeyBindingHelper.registerKeyBinding(new MyKeyBinding());

    // 注册客户端事件监听
    ClientPlayConnectionEvents.JOIN.register((handler, client, server) -> {
        client.execute(() -> LOGGER.info("Joined server!"));
    });
}
```

**文档**: https://docs.fabricmc.net/develop/getting-started/project-structure — 客户端入口点。

---

## 4. Mixin — 类注入（核心机制）

### 4.1 服务端 Mixin

**文件**: `src/main/java/com/betteraerodynamics/mixin/BetterAerodynamicsMixin.java`

```java
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class BetterAerodynamicsMixin {
    @Inject(at = @At("HEAD"), method = "loadLevel")
    private void init(CallbackInfo info) {
        // 在 MinecraftServer.loadLevel() 方法开头注入代码
    }
}
```

### Mixin 核心注解详解

#### `@Mixin(TargetClass.class)`

| 属性 | 说明 |
|------|------|
| **作用** | 声明要注入的目标类 |
| **参数** | 目标类的 Class 对象（必须是 Minecraft 或 Fabric API 中的类） |
| **文档**: https://docs.fabricmc.net/develop/mixins/bytecode — Mixin 操作于 Java 字节码 |

#### `@Inject(at = @At("HEAD"), method = "methodName")`

| 属性 | 说明 |
|------|------|
| **作用** | 在目标方法的特定点注入代码 |
| `at` | 注入点：`"HEAD"`（方法开头）、`"TAIL"`（方法结尾）、`"RETURN"`（返回前）等 |
| `method` | 要注入的目标方法名（Mojang 混淆后的名称，如 `"loadLevel"`、`"run"`） |

#### `CallbackInfo` — 回调信息对象

```java
private void init(CallbackInfo info) {
    // info.cancel() — 取消原方法的执行
}
```

| 方法 | 说明 |
|------|------|
| `info.cancel()` | 取消被注入方法的后续执行 |
| `info.getReturnValue()` | 获取被注入方法的返回值（需配合 `@ModifyReturnValue`） |

### 常见注入点 (At)

| At 值 | 说明 |
|-------|------|
| `"HEAD"` | 方法入口处 |
| `"TAIL"` | 方法出口处（正常返回后） |
| `"RETURN"` | return 指令前 |
| `"INVOKE"` | 调用其他方法时 |
| `"FIELD"` | 访问字段时 |

### 服务端 Mixin vs 客户端 Mixin

| 类型 | 位置 | 环境 | 示例目标类 |
|------|------|------|-----------|
| **服务端** | `src/main/java/...` | 服务端 + 客户端 | `MinecraftServer.class`, `Player.class` |
| **客户端** | `src/client/java/...` | 仅客户端 | `Minecraft.class`, `ClientPlayNetworkHandler.class`（旧版）/ `ServerPlayNetworkHandler.class`（MC 1.20+） |

**文档**: https://docs.fabricmc.net/develop/class-tweakers/interface-injection — Mixin 注入类型。

---

## 5. fabric.mod.json — 模组配置文件

**文件**: `src/main/resources/fabric.mod.json`

```json
{
    "schemaVersion": 1,
    "id": "betteraerodynamics",
    "version": "${version}",
    "name": "Better Aerodynamics",
    "description": "...",
    "authors": ["Me!"],
    "contact": { ... },
    "license": "CC0-1.0",
    "icon": "assets/betteraerodynamics/icon.png",
    "environment": "*",
    "entrypoints": {
        "main": ["com.betteraerodynamics.BetterAerodynamics"],
        "client": ["com.betteraerodynamics.client.BetterAerodynamicsClient"]
    },
    "mixins": [
        "betteraerodynamics.mixins.json",
        {"config": "betteraerodynamics.client.mixins.json", "environment": "client"}
    ],
    "depends": {
        "fabricloader": ">=0.19.3",
        "minecraft": "~26.1.2",
        "java": ">=25",
        "fabric-api": "*"
    }
}
```

### 字段详解

| 字段 | 类型 | 说明 |
|------|------|------|
| `schemaVersion` | int | Schema 版本，固定为 1 |
| `id` | string | **模组唯一 ID**（小写） |
| `version` | string | 模组版本号（由 Gradle 自动替换 `${version}`） |
| `name` | string | 模组显示名称 |
| `description` | string | 模组描述 |
| `authors` | string[] | 作者列表 |
| `contact` | object | 联系方式（homepage, sources 等） |
| `license` | string | 许可证标识符 |
| `icon` | string | 图标路径（相对于 resources 根目录） |
| `environment` | string | 运行环境：`*`(所有)、`client`(仅客户端)、`server`(仅服务端) |

### entrypoints — 入口点配置

| 入口点 | 对应接口 | 说明 |
|--------|----------|------|
| `"main"` | `ModInitializer` | **通用入口**，服务端+客户端都调用 |
| `"client"` | `ClientModInitializer` | **仅客户端入口**，渲染/GUI/音效等 |

```json
"entrypoints": {
    "main": ["com.betteraerodynamics.BetterAerodynamics"],
    "client": ["com.betteraerodynamics.client.BetterAerodynamicsClient"]
}
```

### mixins — Mixin 配置

| 值 | 说明 |
|----|------|
| `"betteraerodynamics.mixins.json"` | 服务端+客户端都加载的 mixin 配置文件 |
| `{"config": "betteraerodynamics.client.mixins.json", "environment": "client"}` | 仅客户端加载的 mixin 配置 |

### depends — 依赖声明

```json
"depends": {
    "fabricloader": ">=0.19.3",   // Fabric Loader 最低版本
    "minecraft": "~26.1.2",       // Minecraft 版本（~表示精确匹配）
    "java": ">=25",               // Java 最低版本
    "fabric-api": "*"             // Fabric API（任意版本）
}
```

**文档**: https://docs.fabricmc.net/develop/loader/fabric-mod-json — fabric.mod.json 完整规范。

---

## 6. Mixin 配置文件

### `src/main/resources/betteraerodynamics.mixins.json` (服务端+通用)

```json
{
    "required": true,
    "package": "com.betteraerodynamics.mixin",
    "compatibilityLevel": "JAVA_25",
    "mixins": [
        "BetterAerodynamicsMixin"
    ],
    "client": [],
    "injectors": {
        "defaultRequire": 1
    }
}
```

### `src/client/resources/betteraerodynamics.client.mixins.json` (仅客户端)

```json
{
    "required": true,
    "package": "com.betteraerodynamics.client.mixin",
    "compatibilityLevel": "JAVA_25",
    "mixins": [],
    "client": [
        "BetterAerodynamicsClientMixin"
    ],
    "injectors": {
        "defaultRequire": 1
    }
}
```

### Mixin JSON 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `required` | boolean | 是否必需（true = 缺少则模组不加载） |
| `package` | string | Mixin 类的包路径 |
| `compatibilityLevel` | string | Java 兼容性级别，如 `"JAVA_25"` |
| `mixins` | string[] | **服务端+客户端**都加载的 mixin 类列表 |
| `client` | string[] | **仅客户端**加载的 mixin 类列表 |
| `injectors` | object | 注入器配置（defaultRequire = 必需的 @Inject 数量） |

---

## 7. Fabric API 事件系统

### 注册事件监听器

```java
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

// 在 onInitialize() 中注册
@Override
public void onInitialize() {
    // 方块破坏事件
    PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
        LOGGER.info("Player broke block at " + pos);
        return true;
    });

    // 自定义网络包处理
    ServerPlayNetworking.registerGlobalReceiver(CUSTOM_PAYLOAD_TYPE, (server, player, handler, payload, responder) -> {
        server.execute(() -> {
            // 处理收到的数据包
        });
    });
}
```

### 常用事件（Fabric API）

| 事件类 | 用途 | 文档位置 |
|--------|------|----------|
| `PlayerBlockBreakEvents` | 方块破坏前后 | https://docs.fabricmc.net/develop/events |
| `ServerPlayConnectionEvents` | 玩家连接/断开 | https://docs.fabricmc.net/develop/networking |
| `LivingEntityEvents` | 生物受伤/死亡 | https://docs.fabricmc.net/develop/events |
| `TickEvents` | 服务端/客户端 tick | https://docs.fabricmc.net/develop/events |

---

## 8. Registry — 注册表（物品、方块等）

```java
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.item.Item;
import net.minecraft.block.Block;

@Override
public void onInitialize() {
    // 注册自定义物品
    Item myItem = Registry.register(
        Registries.ITEM,
        id("my_item"),
        new Item(new Item.Settings())
    );

    // 注册自定义方块
    Block myBlock = Registry.register(
        Registries.BLOCK,
        id("my_block"),
        new MyBlock(Block.Settings.create().strength(2.0f))
    );
}
```

### 常用 Registries

| Registry | 用途 |
|----------|------|
| `Registries.ITEM` | 物品注册表 |
| `Registries.BLOCK` | 方块注册表 |
| `Registries.ENTITY_TYPE` | 实体类型注册表 |
| `Registries.SOUND_EVENT` | 音效事件注册表 |

---

## 9. Gradle 构建配置

### `build.gradle` — 关键配置

```groovy
// Java 版本（Minecraft 26.1 需要 Java 25）
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Loom 插件配置
loom {
    splitEnvironmentSourceSets()  // 分离服务端/客户端源码集
    mods {
        "betteraerodynamics" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}
```

### `gradle.properties` — 版本配置

```properties
minecraft_version=26.1.2       # Minecraft 版本
loader_version=0.19.3          # Fabric Loader 版本
loom_version=1.17-SNAPSHOT     # Fabric Loom 版本
mod_version=1.0.0              # 模组版本号（发布时修改）
fabric_api_version=0.153.0+26.1.2  # Fabric API 版本
```

### 常用 Gradle 命令

| 命令 | 说明 |
|------|------|
| `./gradlew build` | 编译并打包模组 JAR |
| `./gradlew runClient` | 启动开发用客户端（含模组） |
| `./gradlew runServer` | 启动开发用服务端 |
| `./gradlew clean` | 清理构建产物 |

---

## 10. 资源文件结构

```
src/main/resources/
├── fabric.mod.json              # 模组配置（必需）
├── betteraerodynamics.mixins.json            # Mixin 配置（服务端+通用）
├── assets/betteraerodynamics/
│   ├── icon.png                 # 模组图标
│   ├── lang/                    # 语言文件
│   │   └── zh_cn.json           # 中文本地化
│   ├── textures/                # 纹理
│   │   ├── block/               # 方块纹理
│   │   ├── item/                # 物品纹理
│   │   └── entity/              # 实体纹理
│   ├── models/block/            # 方块模型 JSON
│   ├── models/item/             # 物品模型 JSON
│   └── sounds.json              # 音效定义
└── data/betteraerodynamics/                  # 数据生成（标签、战利品表等）
    ├── tags/blocks/
    └── loot_tables/
```

---

## 📖 快速参考：文档链接汇总

| 主题 | 文档 URL |
|------|----------|
| **开发者指南总览** | https://docs.fabricmc.net/develop/ |
| **项目结构** | https://docs.fabricmc.net/develop/getting-started/project-structure |
| **fabric.mod.json** | https://docs.fabricmc.net/develop/loader/fabric-mod-json |
| **Fabric Loader** | https://docs.fabricmc.net/develop/loader/ |
| **Mixin 教程** | https://docs.fabricmc.net/develop/mixins/bytecode |
| **Mixin 注入类型** | https://docs.fabricmc.net/develop/class-tweakers/interface-injection |
| **事件系统** | https://docs.fabricmc.net/develop/events |
| **网络通信** | https://docs.fabricmc.net/develop/networking |
| **调试指南** | https://docs.fabricmc.net/develop/debugging |
| **渲染（世界）** | https://docs.fabricmc.net/develop/rendering/world |
| **自定义实体** | https://docs.fabricmc.net/develop/entities/first-entity |
| **自定义方块模型** | https://docs.fabricmc.net/develop/blocks/block-models |
| **自定义物品模型** | https://docs.fabricmc.net/develop/items/item-models |
| **自定义工具** | https://docs.fabricmc.net/develop/items/custom-tools |
| **自定义流体** | https://docs.fabricmc.net/develop/fluids/first-fluid |
| **自定义音效** | https://docs.fabricmc.net/develop/sounds/custom |
| **自定义数据组件** | https://docs.fabricmc.net/develop/items/custom-data-components |
| **块实体渲染器** | https://docs.fabricmc.net/develop/blocks/block-entity-renderer |
| **Loom 插件** | https://docs.fabricmc.net/develop/loom/ |
| **迁移到 26.1** | https://docs.fabricmc.net/develop/porting/ |
