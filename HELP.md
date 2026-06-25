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
