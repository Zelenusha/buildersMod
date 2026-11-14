# Журнал загрузок и установок

Локальные инструменты хранятся в `.tools`, кэш Gradle — в каталоге пользователя или в указанном `GRADLE_USER_HOME`. В репозитории хранятся настройки сборки, скрипты и Gradle Wrapper.

| Дата | Что | Действие и проверка | Источник |
| --- | --- | --- | --- |
| 2026-11-11 | Minecraft Java 1.20.1 Server | Загружен во временный каталог для получения официального реестра блоков. SHA-1 `84194a2f286ef7c14ed7ce0090dba59902951553` сверена с манифестом. | [Манифест Mojang](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) |
| 2026-11-13 | Forge MDK 1.20.1-47.4.28 | Загружен и распакован во временный каталог. SHA-1 `eee9145e892bb5ca5fe72323bd81c8522c53417f` совпала с опубликованной. | [Страница Forge 1.20.1](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) |
| 2026-11-13 | Eclipse Temurin JDK 17.0.21+9, Windows x64 | ZIP загружен, SHA-256 `98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b` проверена по API. JDK распакован в `.tools/jdk/jdk-17.0.21+9`, запуск `java -version` подтверждён. | [Релиз Adoptium](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9) |
| 2026-11-13 | Gradle 8.8 | Gradle Wrapper загрузил ZIP в пользовательский кэш, проверил SHA-256 и запустил `--version`. | [Дистрибутив Gradle](https://services.gradle.org/distributions/gradle-8.8-bin.zip) |
| 2026-11-13 | Исходники Forge 1.20.1-47.4.28 | JAR загружен во временный каталог для сверки методов Forge. | [Forge Maven](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.28/forge-1.20.1-47.4.28-sources.jar) |
| 2026-11-13–14 | ForgeGradle 6.0.54 и Minecraft Java 1.20.1 | Gradle загрузил данные MCP, клиентские и серверные JAR, зависимости Forge и библиотеки тестов. Для клиента использованы локальные ресурсы Minecraft и недостающие ресурсы Mojang. Последний недостающий `pack.mcmeta` сверили с SHA-1 индекса. Всё размещено в пользовательском кэше. | [Forge Maven](https://maven.minecraftforge.net), [манифест Mojang](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json), [JUnit](https://junit.org/junit5/) |
| 2026-11-14 | Клиент Forge 1.20.1-47.4.28 | SHA-1 установщика `585dd6071897becaed3b09bff4c3d9559e0cbc75` сверена с сайтом Forge. Установка прошла в `.tools/packaged-client/launcher`. Игровое окружение и единственный JAR мода размещены в `.tools/packaged-client/game`. | [Официальные загрузки Forge](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) |
