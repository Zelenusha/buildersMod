# Журнал загрузок и установок на машине Николая

Отдельный журнал для второй рабочей машины (Windows 11, ветка `claude/nikolay`). Записи первой машины остаются в [INSTALL_LOG.md](INSTALL_LOG.md).

Локальные инструменты лежат в `.tools` внутри рабочей копии и в репозиторий не попадают. Кэш Gradle лежит в каталоге пользователя `~/.gradle`. В систему (PATH, реестр, `Program Files`) ничего не устанавливалось.

До начала работ на машине уже были: Git 2.45.1, GitHub CLI 2.96.0, Microsoft OpenJDK 21.0.9 (в PATH и `JAVA_HOME`), OpenJDK 24.0.1 в `~/.jdks`, лаунчер Minecraft. JDK 17 отсутствовал.

| Дата | Что | Действие и проверка | Источник |
| --- | --- | --- | --- |
| 2026-11-15 | Eclipse Temurin JDK 17.0.21+9, Windows x64 | ZIP (191 117 380 байт) загружен в `.tools/downloads/jdk17.zip`. SHA-256 `98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b` совпала с опубликованным `.sha256.txt` и с записью первой машины. Распакован в `.tools/jdk/jdk-17.0.21+9`, `java -version` показывает `17.0.21+9`. Сборка запускается с `JAVA_HOME`, указывающим на этот каталог. | [Релиз Adoptium](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9) |
| 2026-11-15 | Gradle 8.8 | Gradle Wrapper из репозитория сам загрузил дистрибутив в `~/.gradle/wrapper/dists` и проверил SHA-256 из `gradle-wrapper.properties`. | [Дистрибутив Gradle](https://services.gradle.org/distributions/gradle-8.8-bin.zip) |
| 2026-11-15 | ForgeGradle 6.0.54, Forge 1.20.1-47.4.28, Minecraft 1.20.1, JUnit 5.11.4 | Первая сборка `gradlew build` загрузила в `~/.gradle/caches` плагин ForgeGradle, данные MCP, клиентский и серверный JAR Minecraft, библиотеки Forge и JUnit. Сборка завершилась успешно за 4 мин 49 с, прошли 15 проверок JUnit. `gradlew runGameTestServer` прошёл 36 из 36 Forge GameTest. | [Forge Maven](https://maven.minecraftforge.net), [манифест Mojang](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json), [Maven Central](https://repo.maven.apache.org/maven2/) |
