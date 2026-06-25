# Setup Notes

## Database — init.sql

`init.sql` is automatically executed when the MySQL container starts for the first time (mounted at `/docker-entrypoint-initdb.d/`). No manual steps are needed.

To reset the database (drop and recreate):
```bash
docker-compose down -v
docker-compose up -d
```

---

## RocketMQ — broker.conf

`broker.conf` is mounted into the RocketMQ broker container at startup. Key settings:

| Key | Value | Notes |
|---|---|---|
| `listenPort` | `10911` | Must match the port exposed in docker-compose |
| `namesrvAddr` | `rocketmq-namesrv:9876` | Points to the namesrv container by service name |
| `brokerRole` | `ASYNC_MASTER` | Single-node async master (suitable for local dev) |
| `flushDiskType` | `ASYNC_FLUSH` | Async flush for better write performance |
| `fileReservedTime` | `48` | Commit log retained for 48 hours |
| `brokerIP1` | `127.0.0.1` | The IP the broker registers with the namesrv (see note below) |
| `autoCreateTopicEnable` | `true` | Auto-create topics on first use (dev only; disable in production) |

### brokerIP1

When the broker starts, it registers its address with the namesrv. Clients ask the namesrv for that address and connect to it directly — so the registered IP must be reachable by whoever is connecting.

`brokerIP1 = 127.0.0.1` is used here to ensure the Spring Boot app (running on the host) can always reach the broker via the mapped port `localhost:10911`.

**Known limitation — RocketMQ Console on Mac**

The console runs inside Docker. When it asks namesrv for the broker address, it gets back `127.0.0.1:10911`. Inside a container, `127.0.0.1` points to the container itself — not the host — so the console cannot connect to the broker directly. Topic listing and cluster stats will show errors.

This is a Mac Docker Desktop networking constraint. The Spring Boot service is unaffected.

**Fix (optional, one-time):** add `host.docker.internal` to the host's `/etc/hosts`, then change `brokerIP1` to `host.docker.internal`:

```bash
sudo sh -c 'echo "127.0.0.1 host.docker.internal" >> /etc/hosts'
```

On Windows (Docker Desktop + WSL2), `host.docker.internal` works from both host and containers, so this limitation does not apply.

