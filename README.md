# SellWorth

Paper 1.21+ plugin: `/sell` GUI, `/worth` GUI and hover worth, all driven by `prices.yml`.

**Requires:** Vault + an economy plugin. **Optional:** ProtocolLib (hover worth on items).

## Commands
| Command | Description |
|---|---|
| `/sell` | Open the sell GUI, click the green pane to sell |
| `/worth [search]` | Browse item prices (search, sort, pages) |
| `/sellreload` | Reload `prices.yml` |

## Building
GitHub Actions builds it on every push (Actions tab > latest run > Artifacts).
Push a tag like `v1.0.0` to publish the jar as a Release.

Locally: `mvn package` (JDK 21) gives `target/SellWorth.jar`.
